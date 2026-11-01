-- CAMPUS CONNECT : database schema (MySQL 8+)
-- Run:  mysql -u root -p < database/schema.sql

CREATE DATABASE IF NOT EXISTS campus_connect
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE campus_connect;

CREATE TABLE IF NOT EXISTS users (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(120) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  salt          VARCHAR(64)  NOT NULL,
  role          ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
  department    VARCHAR(80),
  phone         VARCHAR(20),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS categories (
  id   INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(60) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS complaints (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  user_id     INT NOT NULL,
  category_id INT NOT NULL,
  title       VARCHAR(150) NOT NULL,
  description TEXT NOT NULL,
  priority    ENUM('LOW','MEDIUM','HIGH') NOT NULL DEFAULT 'MEDIUM',
  status      ENUM('PENDING','IN_PROGRESS','RESOLVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id)     REFERENCES users(id)      ON DELETE CASCADE,
  FOREIGN KEY (category_id) REFERENCES categories(id)
);

CREATE TABLE IF NOT EXISTS complaint_history (
  id           INT AUTO_INCREMENT PRIMARY KEY,
  complaint_id INT NOT NULL,
  status       ENUM('PENDING','IN_PROGRESS','RESOLVED','REJECTED') NOT NULL,
  remarks      VARCHAR(500),
  changed_by   INT NOT NULL,
  changed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (complaint_id) REFERENCES complaints(id) ON DELETE CASCADE,
  FOREIGN KEY (changed_by)   REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS notifications (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  user_id    INT NOT NULL,
  message    VARCHAR(300) NOT NULL,
  is_read    TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

INSERT IGNORE INTO categories (name) VALUES
  ('Hostel'), ('Academics'), ('Infrastructure'), ('Canteen'),
  ('Library'), ('Transport'), ('IT & Network'), ('Other');

-- The default admin account (admin@campus.com / admin123) is created
-- automatically by the Java server the first time it starts.
