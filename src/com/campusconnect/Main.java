package com.campusconnect;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.Executors;

/** Starts the Campus Connect web server on http://localhost:8080 */
public class Main {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css",  "text/css; charset=utf-8",
            "js",   "application/javascript; charset=utf-8",
            "svg",  "image/svg+xml",
            "png",  "image/png",
            "ico",  "image/x-icon");

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        seedAdmin();

        Path webRoot = Paths.get(System.getenv().getOrDefault("WEB_DIR", "web")).toAbsolutePath().normalize();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new Api());
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = webRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                Api.send(ex, 404, "text/plain", "Not found".getBytes());
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            Api.send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
        });
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Campus Connect running at http://localhost:" + port);
        System.out.println("Admin login: admin@campus.com / admin123  (change it after first login)");
    }

    /** Creates the default administrator on first run. */
    private static void seedAdmin() throws Exception {
        if (!Db.query("SELECT id FROM users WHERE role = 'ADMIN'").isEmpty()) return;
        String salt = Security.newSalt();
        Db.update("INSERT INTO users (name, email, password_hash, salt, role, department) VALUES (?,?,?,?, 'ADMIN', ?)",
                "Campus Admin", "admin@campus.com", Security.hash("admin123", salt), salt, "Administration");
        System.out.println("Default admin account created.");
    }
}
