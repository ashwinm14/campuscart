package com.campusconnect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * All /api/* endpoints:
 *   POST register | login | logout          GET me | categories
 *   GET/POST complaints, complaints/{id}, complaints/{id}/status (admin)
 *   GET notifications, POST notifications/read
 *   GET reports (admin), reports/csv (admin)
 */
public class Api implements HttpHandler {

    static class ApiException extends RuntimeException {
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<String> STATUSES   = Set.of("PENDING", "IN_PROGRESS", "RESOLVED", "REJECTED");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String COMPLAINT_SELECT =
            "SELECT c.id, c.title, c.description, c.priority, c.status, c.created_at, c.updated_at, "
          + "cat.name AS category, u.name AS user_name, u.email AS user_email, c.user_id "
          + "FROM complaints c JOIN categories cat ON cat.id = c.category_id "
          + "JOIN users u ON u.id = c.user_id ";

    // ------------------------------------------------------------------ entry point
    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            Map<String, String> p = params(ex);
            if (ex.getRequestURI().getPath().equals("/api/reports/csv")) {
                csv(ex);
                return;
            }
            send(ex, 200, "application/json", Json.toJson(route(ex, p)).getBytes(StandardCharsets.UTF_8));
        } catch (ApiException e) {
            send(ex, e.code, "application/json", Json.toJson(Map.of("error", e.getMessage())).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", Json.toJson(Map.of("error", "Server error. Please try again.")).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Object route(HttpExchange ex, Map<String, String> p) throws Exception {
        String method = ex.getRequestMethod();
        String[] s = ex.getRequestURI().getPath().substring(5).split("/"); // strip "/api/"
        switch (s[0]) {
            case "register":   need("POST", method); return register(p);
            case "login":      need("POST", method); return login(ex, p);
            case "logout":     need("POST", method); return logout(ex);
            case "me":         return requireUser(ex);
            case "categories": requireUser(ex);
                               return Db.query("SELECT id, name FROM categories ORDER BY name");
            case "complaints": return complaints(ex, s, p, method);
            case "notifications": return notifications(ex, s, method);
            case "reports":    requireAdmin(ex); return reports();
            default: throw new ApiException(404, "Not found");
        }
    }

    // ------------------------------------------------------------------ auth
    private Object register(Map<String, String> p) throws SQLException {
        String name = val(p, "name"), email = val(p, "email").toLowerCase(), pass = val(p, "password");
        if (name.isEmpty() || name.length() > 100) throw new ApiException(400, "Enter your name (max 100 characters).");
        if (!EMAIL.matcher(email).matches()) throw new ApiException(400, "Enter a valid email address.");
        if (pass.length() < 6) throw new ApiException(400, "Password must be at least 6 characters.");
        if (!Db.query("SELECT id FROM users WHERE email = ?", email).isEmpty())
            throw new ApiException(409, "An account with this email already exists.");

        String salt = Security.newSalt();
        Db.update("INSERT INTO users (name, email, password_hash, salt, role, department, phone) VALUES (?,?,?,?, 'USER', ?, ?)",
                name, email, Security.hash(pass, salt), salt, val(p, "department"), val(p, "phone"));
        return Map.of("ok", true);
    }

    private Object login(HttpExchange ex, Map<String, String> p) throws SQLException {
        List<Map<String, Object>> rows = Db.query(
                "SELECT id, name, email, role, department, phone, password_hash, salt FROM users WHERE email = ?",
                val(p, "email").toLowerCase());
        if (rows.isEmpty()) throw new ApiException(401, "Incorrect email or password.");
        Map<String, Object> u = rows.get(0);
        String attempt = Security.hash(val(p, "password"), (String) u.get("salt"));
        if (!attempt.equals(u.get("password_hash"))) throw new ApiException(401, "Incorrect email or password.");

        String token = Security.newToken();
        SESSIONS.put(token, ((Number) u.get("id")).longValue());
        ex.getResponseHeaders().add("Set-Cookie", "cc_token=" + token + "; Path=/; HttpOnly; SameSite=Lax");
        u.remove("password_hash");
        u.remove("salt");
        return u;
    }

    private Object logout(HttpExchange ex) {
        String t = token(ex);
        if (t != null) SESSIONS.remove(t);
        ex.getResponseHeaders().add("Set-Cookie", "cc_token=; Path=/; Max-Age=0");
        return Map.of("ok", true);
    }

    private String token(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("cc_token")) return kv[1];
        }
        return null;
    }

    private Map<String, Object> requireUser(HttpExchange ex) throws SQLException {
        String t = token(ex);
        Long id = t == null ? null : SESSIONS.get(t);
        if (id == null) throw new ApiException(401, "Please log in to continue.");
        List<Map<String, Object>> rows = Db.query(
                "SELECT id, name, email, role, department, phone FROM users WHERE id = ?", id);
        if (rows.isEmpty()) throw new ApiException(401, "Please log in to continue.");
        return rows.get(0);
    }

    private Map<String, Object> requireAdmin(HttpExchange ex) throws SQLException {
        Map<String, Object> u = requireUser(ex);
        if (!"ADMIN".equals(u.get("role"))) throw new ApiException(403, "Only administrators can do this.");
        return u;
    }

    // ------------------------------------------------------------------ complaints
    private Object complaints(HttpExchange ex, String[] s, Map<String, String> p, String method) throws SQLException {
        Map<String, Object> user = requireUser(ex);
        boolean admin = "ADMIN".equals(user.get("role"));
        long uid = ((Number) user.get("id")).longValue();

        // /api/complaints
        if (s.length == 1) {
            if (method.equals("POST")) return createComplaint(uid, p);
            StringBuilder sql = new StringBuilder(COMPLAINT_SELECT + "WHERE 1=1 ");
            List<Object> args = new ArrayList<>();
            if (!admin) { sql.append("AND c.user_id = ? "); args.add(uid); }
            String status = val(p, "status");
            if (STATUSES.contains(status)) { sql.append("AND c.status = ? "); args.add(status); }
            String q = val(p, "q");
            if (!q.isEmpty()) {
                sql.append("AND (c.title LIKE ? OR c.description LIKE ? OR u.name LIKE ?) ");
                String like = "%" + q + "%";
                args.add(like); args.add(like); args.add(like);
            }
            sql.append("ORDER BY c.created_at DESC, c.id DESC");
            return Db.query(sql.toString(), args.toArray());
        }

        // /api/complaints/{id}
        long id = parseId(s[1]);
        List<Map<String, Object>> rows = Db.query(COMPLAINT_SELECT + "WHERE c.id = ?", id);
        if (rows.isEmpty()) throw new ApiException(404, "Complaint not found.");
        Map<String, Object> c = rows.get(0);
        if (!admin && ((Number) c.get("user_id")).longValue() != uid)
            throw new ApiException(403, "You can only view your own complaints.");

        // /api/complaints/{id}/status
        if (s.length == 3 && s[2].equals("status")) {
            need("POST", method);
            if (!admin) throw new ApiException(403, "Only administrators can update status.");
            String status = val(p, "status"), remarks = val(p, "remarks");
            if (!STATUSES.contains(status)) throw new ApiException(400, "Choose a valid status.");
            if (remarks.length() > 500) throw new ApiException(400, "Remarks are limited to 500 characters.");
            Db.update("UPDATE complaints SET status = ? WHERE id = ?", status, id);
            Db.update("INSERT INTO complaint_history (complaint_id, status, remarks, changed_by) VALUES (?,?,?,?)",
                    id, status, remarks, uid);
            Db.update("INSERT INTO notifications (user_id, message) VALUES (?, ?)",
                    c.get("user_id"),
                    "Your complaint #" + id + " \"" + shorten((String) c.get("title")) + "\" is now "
                            + status.replace('_', ' ').toLowerCase() + ".");
            return Map.of("ok", true);
        }

        c.put("history", Db.query(
                "SELECT h.status, h.remarks, h.changed_at, u.name AS changed_by "
              + "FROM complaint_history h JOIN users u ON u.id = h.changed_by "
              + "WHERE h.complaint_id = ? ORDER BY h.id", id));
        return c;
    }

    private Object createComplaint(long uid, Map<String, String> p) throws SQLException {
        String title = val(p, "title"), desc = val(p, "description");
        String priority = val(p, "priority").toUpperCase();
        if (title.isEmpty() || title.length() > 150) throw new ApiException(400, "Enter a title (max 150 characters).");
        if (desc.length() < 10 || desc.length() > 2000) throw new ApiException(400, "Describe the problem in 10 to 2000 characters.");
        if (!PRIORITIES.contains(priority)) priority = "MEDIUM";
        long cat = parseId(val(p, "category_id"));
        if (Db.query("SELECT id FROM categories WHERE id = ?", cat).isEmpty())
            throw new ApiException(400, "Choose a category.");

        long id = Db.update("INSERT INTO complaints (user_id, category_id, title, description, priority) VALUES (?,?,?,?,?)",
                uid, cat, title, desc, priority);
        Db.update("INSERT INTO complaint_history (complaint_id, status, remarks, changed_by) VALUES (?, 'PENDING', 'Complaint registered', ?)",
                id, uid);
        Db.update("INSERT INTO notifications (user_id, message) SELECT id, ? FROM users WHERE role = 'ADMIN'",
                "New " + priority.toLowerCase() + "-priority complaint #" + id + ": " + shorten(title));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        return out;
    }

    // ------------------------------------------------------------------ notifications
    private Object notifications(HttpExchange ex, String[] s, String method) throws SQLException {
        long uid = ((Number) requireUser(ex).get("id")).longValue();
        if (s.length == 2 && s[1].equals("read")) {
            need("POST", method);
            Db.update("UPDATE notifications SET is_read = 1 WHERE user_id = ?", uid);
            return Map.of("ok", true);
        }
        List<Map<String, Object>> items = Db.query(
                "SELECT id, message, is_read, created_at FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 30", uid);
        long unread = ((Number) Db.query(
                "SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND is_read = 0", uid).get(0).get("n")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", unread);
        out.put("items", items);
        return out;
    }

    // ------------------------------------------------------------------ reports
    private Object reports() throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", Db.query("SELECT COUNT(*) AS n FROM complaints").get(0).get("n"));
        out.put("byStatus", Db.query("SELECT status AS label, COUNT(*) AS cnt FROM complaints GROUP BY status"));
        out.put("byCategory", Db.query(
                "SELECT cat.name AS label, COUNT(*) AS cnt FROM complaints c "
              + "JOIN categories cat ON cat.id = c.category_id GROUP BY cat.name ORDER BY cnt DESC"));
        out.put("byPriority", Db.query("SELECT priority AS label, COUNT(*) AS cnt FROM complaints GROUP BY priority"));
        out.put("avgResolutionHours", Db.query(
                "SELECT ROUND(AVG(TIMESTAMPDIFF(HOUR, created_at, updated_at)), 1) AS h "
              + "FROM complaints WHERE status = 'RESOLVED'").get(0).get("h"));
        return out;
    }

    private void csv(HttpExchange ex) throws Exception {
        requireAdmin(ex);
        StringBuilder sb = new StringBuilder("ID,Title,Category,Priority,Status,Submitted By,Email,Created,Updated\n");
        for (Map<String, Object> r : Db.query(COMPLAINT_SELECT + "ORDER BY c.id")) {
            String[] cols = {"id", "title", "category", "priority", "status", "user_name", "user_email", "created_at", "updated_at"};
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(String.valueOf(r.get(cols[i])).replace("\"", "\"\"")).append('"');
            }
            sb.append('\n');
        }
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"complaints-report.csv\"");
        send(ex, 200, "text/csv; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers
    private static void need(String expected, String actual) {
        if (!expected.equals(actual)) throw new ApiException(405, "Method not allowed.");
    }

    private static String val(Map<String, String> p, String key) {
        String v = p.get(key);
        return v == null ? "" : v.trim();
    }

    private static long parseId(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { throw new ApiException(400, "Invalid id."); }
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        parseForm(ex.getRequestURI().getRawQuery(), m);
        if (!ex.getRequestMethod().equals("GET"))
            parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), m);
        return m;
    }

    private static void parseForm(String s, Map<String, String> out) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }
}
