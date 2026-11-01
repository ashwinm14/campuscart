package com.campusconnect;

import java.sql.*;
import java.util.*;

/** Small JDBC helper: opens connections and runs parameterised (SQL-injection safe) queries. */
public class Db {

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    static final String URL  = env("DB_URL",
            "jdbc:mysql://localhost:3306/campus_connect?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    static final String USER = env("DB_USER", "root");
    // static final String PASS = env("DB_PASS", "root");
    static final String PASS = System.getenv("DB_PASS") != null ? System.getenv("DB_PASS") : "";

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    /** Runs a SELECT and returns every row as a column-name -> value map. */
    static List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++)
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Runs INSERT / UPDATE / DELETE. Returns the generated key for inserts (or 0). */
    static long update(String sql, Object... params) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }
}
