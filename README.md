# Campus Connect — Complaint Registration & Tracking System

S3 CSE · Anashna PS (13) · Lamiya Fathima (33) · Vismaya Shibin (58)

A web application where users register and track complaints and administrators manage and resolve them.

| Layer | Technology |
|---|---|
| User interface | HTML, CSS |
| Interactivity | JavaScript (single-page app, `fetch` calls) |
| Server-side | Java 17 (built-in `com.sun.net.httpserver`, no framework needed) |
| Database access | JDBC (`PreparedStatement`, so no SQL injection) |
| Storage | MySQL 8 |

## Features
- **Authentication**: register / login / logout, PBKDF2-salted password hashes, HttpOnly session cookie
- **Roles**: `USER` and `ADMIN`, enforced on the server for every request
- **Complaint categorisation**: Hostel, Academics, Infrastructure, Canteen, Library, Transport, IT & Network, Other
- **Priority levels**: Low / Medium / High
- **Status tracking**: Pending → In progress → Resolved / Rejected, with a full timeline and admin remarks
- **Notifications**: admins are alerted to new complaints; users are alerted on every status change (bell icon, auto-refresh)
- **Search and filter** complaints by text and status
- **Reports** (admin): totals, by status / priority / category, average resolution time, **CSV download**

## Setup

### 1. Requirements
- JDK 17 or newer
- MySQL 8 running locally
- MySQL Connector/J jar: download from https://dev.mysql.com/downloads/connector/j/ (choose "Platform Independent", extract `mysql-connector-j-x.x.x.jar`) and put it in a `lib/` folder inside this project.

### 2. Create the database
```
mysql -u root -p < database/schema.sql
```

### 3. Compile
Windows:
```
mkdir out
javac -d out src\com\campusconnect\*.java
```
Linux / macOS:
```
mkdir -p out && javac -d out src/com/campusconnect/*.java
```

### 4. Run (from the project root folder)
Windows (PowerShell):
```
$env:DB_USER="root"; $env:DB_PASS="your_mysql_password"
java -cp "out;lib/mysql-connector-j-8.4.0.jar" com.campusconnect.Main
```
Linux / macOS:
```
DB_USER=root DB_PASS=your_mysql_password java -cp "out:lib/mysql-connector-j-8.4.0.jar" com.campusconnect.Main
```
(Use your actual jar file name.) Then open **http://localhost:8080**

Optional environment variables: `DB_URL`, `DB_USER`, `DB_PASS`, `PORT`, `WEB_DIR`.

### 5. Log in
- **Admin** (created automatically on first start): `admin@campus.com` / `admin123`
- **User**: click *Create account* on the login screen

## Project structure
```
campus-connect/
├── database/schema.sql            tables + seed categories
├── src/com/campusconnect/
│   ├── Main.java                  starts server, serves web/, seeds admin
│   ├── Api.java                   all REST endpoints and business rules
│   ├── Db.java                    JDBC connection + query helpers
│   ├── Security.java              password hashing, session tokens
│   └── Json.java                  small JSON writer
└── web/
    ├── index.html                 page shell
    ├── style.css                  styling (responsive)
    └── app.js                     UI logic and API calls
```

## Database design
- `users` (id, name, email, password_hash, salt, role, department, phone)
- `categories` (id, name)
- `complaints` (id, user_id → users, category_id → categories, title, description, priority, status, timestamps)
- `complaint_history` (id, complaint_id → complaints, status, remarks, changed_by → users, changed_at)
- `notifications` (id, user_id → users, message, is_read, created_at)

## API summary
| Method & path | Who | Purpose |
|---|---|---|
| POST `/api/register`, `/api/login`, `/api/logout` | all | account handling |
| GET `/api/me`, `/api/categories` | logged in | session info, category list |
| GET/POST `/api/complaints` | logged in | list (own, or all for admin) / create |
| GET `/api/complaints/{id}` | owner or admin | detail + history |
| POST `/api/complaints/{id}/status` | admin | change status with remarks |
| GET `/api/notifications`, POST `/api/notifications/read` | logged in | bell notifications |
| GET `/api/reports`, `/api/reports/csv` | admin | statistics and export |

## Possible future enhancements
Email/SMS alerts, file/photo attachments, connection pooling (HikariCP), password reset, and department-wise admin accounts.
