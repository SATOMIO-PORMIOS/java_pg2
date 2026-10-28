// 組み込みHTTPサーバーを利用します。
import com.sun.net.httpserver.HttpServer;
// サーバーの待ち受けアドレスを指定します。
import java.net.InetSocketAddress;
// フォームのURLエンコード値をデコードします。
import java.net.URLDecoder;
// 文字コードを指定して文字列を変換します。
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDate;
// HTTP入出力の例外を扱います。
import java.io.IOException;
// SQLiteへの接続とSQL実行に使います。
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
// 可変長の一覧を作成します。
// 可変長の一覧を作成します。
import java.util.ArrayList;
// Todoやカテゴリーの一覧を扱います。
import java.util.List;
import java.util.Base64;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// アプリケーション本体です。
public class App {
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final String DEFAULT_APP_TITLE = "\u3084\u308a\u305f\u3044\u3053\u3068\u30ea\u30b9\u30c8";
    static final Path APP_TITLE_FILE = Path.of("app-title.txt").toAbsolutePath().normalize();
    static final String INITIAL_LIST_TITLE = "\u3084\u308a\u305f\u3044\u3053\u3068\u30ea\u30b9\u30c8\uff08\u30d0\u30b1\u30c3\u30c8\u30ea\u30b9\u30c8\uff09";
    static final int PBKDF2_ITERATIONS = 310_000;
    static final String SESSION_COOKIE = "BUCKETSESSION";
    static final long SESSION_IDLE_TIMEOUT_MS = Long.parseLong(System.getenv().getOrDefault("APP_SESSION_IDLE_SECONDS", "1800")) * 1000L;
    static final long ANONYMOUS_SESSION_TIMEOUT_MS = 10L * 60 * 1000;
    static final long SESSION_ABSOLUTE_TIMEOUT_MS = 8L * 60 * 60 * 1000;
    static final long LOGIN_WINDOW_MS = 15L * 60 * 1000;
    static final int MAX_LOGIN_FAILURES = 5;
    static final boolean PRODUCTION_MODE = "production".equalsIgnoreCase(System.getenv("APP_ENV"));
    static final boolean HTTPS_TERMINATED = "true".equalsIgnoreCase(System.getenv("APP_HTTPS_TERMINATED"));
    static final int SERVER_PORT = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "8080"));
    static final Path UPLOAD_DIR = Path.of("uploads", "wishes").toAbsolutePath().normalize();
    static final Path CATEGORY_IMAGE_DIR = Path.of("assets", "categories").toAbsolutePath().normalize();
    // 画面表示に使うTodo一覧を保持します。
    static List<Category> categories = new ArrayList<>();
    // SQLiteへの接続を保持します。
    static Connection connection;
    static final ConcurrentMap<String, AuthSession> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, LoginAttempt> LOGIN_ATTEMPTS = new HashMap<>();

    // データベースを準備し、HTTPサーバーを起動します。
    public static void main(String[] args) throws Exception {
        if (PRODUCTION_MODE && !HTTPS_TERMINATED) {
            throw new IllegalStateException("Production requires HTTPS terminated by a trusted reverse proxy; set APP_HTTPS_TERMINATED=true only after configuring it.");
        }
        // SQLiteデータベースに接続します。
        connection = DriverManager.getConnection("jdbc:sqlite:todos.db");
        try (Statement foreignKeys = connection.createStatement()) {
            foreignKeys.execute("PRAGMA foreign_keys = ON");
        }
        // テーブルを作成し、必要なデータ移行を行います。
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('admin','user')), is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)), list_title TEXT NOT NULL DEFAULT '\u3084\u308a\u305f\u3044\u3053\u3068\u30ea\u30b9\u30c8\uff08\u30d0\u30b1\u30c3\u30c8\u30ea\u30b9\u30c8\uff09', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER NOT NULL, due_date TEXT, budget INTEGER, completed_date TEXT, completed_amount INTEGER, user_id INTEGER REFERENCES users(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)");
            statement.executeUpdate("INSERT OR IGNORE INTO categories (name) VALUES ('\u65c5\u884c\u30fb\u89b3\u5149'), ('\u8da3\u5473\u30fb\u4f53\u9a13'), ('\u30b0\u30eb\u30e1'), ('\u5b66\u7fd2\u30fb\u6311\u6226'), ('\u8cb7\u3044\u7269'), ('\u305d\u306e\u4ed6')");
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                boolean hasCategoryId = false;
                while (columns.next()) { if ("category_id".equals(columns.getString("name"))) hasCategoryId = true; }
                if (!hasCategoryId) statement.executeUpdate("ALTER TABLE todos ADD COLUMN category_id INTEGER REFERENCES categories(id)");
            }
            statement.executeUpdate("UPDATE todos SET category_id = (SELECT id FROM categories WHERE name = '\u305d\u306e\u4ed6') WHERE category_id IS NULL");
        }
        try (Statement imageSchema = connection.createStatement()) {
            boolean hasImagePath = false;
            try (ResultSet columns = imageSchema.executeQuery("PRAGMA table_info(todos)")) {
                while (columns.next()) if ("image_path".equals(columns.getString("name"))) hasImagePath = true;
            }
            if (!hasImagePath) imageSchema.executeUpdate("ALTER TABLE todos ADD COLUMN image_path TEXT");
        }
        ensureUserSchema();
        WishDetails.ensureSchema(connection);
        Files.createDirectories(UPLOAD_DIR);
        Files.createDirectories(CATEGORY_IMAGE_DIR);
        loadCategories();
        // SQLiteからTodo一覧を読み込みます。

        // HTTPサーバーを作成します。
        HttpServer server = HttpServer.create(new InetSocketAddress(SERVER_PORT), 0);
        // HTTPリクエストの処理先を登録します。
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();

                if (path.equals("/favicon.ico")) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }

                if (path.equals("/login") && method.equals("GET")) {
                    showLoginPage(exchange);
                    return;
                } else if (path.equals("/login") && method.equals("POST")) {
                    handleLogin(exchange);
                    return;
                } else if (path.equals("/logout") && method.equals("POST")) {
                    handleLogout(exchange);
                    return;
                }

                if (path.startsWith("/assets/categories/")) {
                    serveImage(exchange, CATEGORY_IMAGE_DIR, path.substring("/assets/categories/".length()));
                    return;
                }

                AuthSession session = requireAuthenticated(exchange);
                if (session == null) return;
                if (AccountManagement.handle(exchange, session)) return;
                if (path.startsWith("/uploads/wishes/")) {
                    serveWishImage(exchange, session, path.substring("/uploads/wishes/".length()));
                    return;
                }


                // Todo新規登録のPOSTリクエストを処理します。
                if (path.equals("/settings/title") && method.equals("POST")) {
                    saveAppTitle(exchange, session);
                    return;
                } else if ((path.equals("/save") || path.equals("/edit")) && method.equals("POST")) {
                    saveTodo(exchange, session);
                    return;
                } else if (path.equals("/edit") && method.equals("GET")) {
                    redirectHome(exchange);
                    return;
                } else if (path.equals("/delete") && method.equals("POST")) {
                    deleteTodo(exchange, session);
                    return;
                } else if (path.equals("/")) {
                    // 一覧画面のHTMLとスタイルを作成します。
                    String appTitle = getAppTitle(session.userId);
                    String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>" + escapeHtml(appTitle) + "</title><link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Yusei+Magic&display=swap' rel='stylesheet'><link rel='stylesheet' href='/assets/app.css'><script src='/assets/app.js' defer></script></head><body><header class='app-header'><h1>" + escapeHtml(appTitle) + "</h1><button type='button' id='appTitleEdit' class='title-edit-button' title='\u30bf\u30a4\u30c8\u30eb\u3092\u5909\u66f4'><img src='/assets/edit.png' alt=''></button><div class='account-actions'><nav class='account-nav'>" + (AccountManagement.isAdmin(session.userId) ? "<a href='/admin/users'>ユーザー管理</a>" : "") + "<a href='/account/password'>パスワード変更</a></nav><form class='logout-form' action='/logout' method='post'><input type='hidden' name='_csrf' value='" + session.csrfToken + "'><button type='submit'>ログアウト</button></form></div></header><button type='button' id='newWishButton' class='new-wish-button'>&#65291; &#26032;&#35215;&#36861;&#21152;</button>";
                    // Todoがない場合の案内を表示します。
                    // Todoがない場合の案内を表示します。
                    String filterQuery = exchange.getRequestURI().getRawQuery();
                    boolean sortByPriority = "priority".equals(parseForm(filterQuery == null ? "" : filterQuery).get("sort"));
                    List<Todo> todos = loadTodos(session.userId, sortByPriority);
                    if (todos.isEmpty()) { html += "<p>\u307e\u3060\u9805\u76ee\u304c\u3042\u308a\u307e\u305b\u3093\u3002\u65b0\u898f\u8ffd\u52a0\u304b\u3089\u767b\u9332\u3057\u3066\u304f\u3060\u3055\u3044\u3002</p>"; }
                    // 達成数と未完了予算を集計します。
                    int completedCount = 0;
                    long pendingBudgetTotal = 0;
                    for (Todo todo : todos) {
                        if (todo.isDone()) { completedCount++; }
                        else if (!todo.getBudget().isEmpty()) { pendingBudgetTotal += Long.parseLong(todo.getBudget()); }
                    }
                    int completionPercent = todos.isEmpty() ? 0 : completedCount * 100 / todos.size();
                    List<String> selectedCategoryIds = new ArrayList<>();
                    List<String> selectedStatuses = new ArrayList<>();
                    if (filterQuery != null) for (String parameter : filterQuery.split("&")) {
                        if (parameter.startsWith("categoryId=")) selectedCategoryIds.add(URLDecoder.decode(parameter.substring(11), StandardCharsets.UTF_8));
                        else if (parameter.startsWith("status=")) selectedStatuses.add(URLDecoder.decode(parameter.substring(7), StandardCharsets.UTF_8));
                    }
                    boolean hasActiveFilters = sortByPriority || !selectedStatuses.isEmpty() || !selectedCategoryIds.isEmpty();
                    html += "<div class='list-content'><div class='overview-row'><section class='progress-summary' aria-label='\u9054\u6210\u7387'><div class='progress-label'><span>\u9054\u6210\u7387</span><span class='progress-values'><span>" + completionPercent + "%</span><span class='progress-counts'>" + completedCount + "件 / " + todos.size() + "件</span></span></div><div class='progress-track' role='progressbar' aria-valuenow='" + completionPercent + "' aria-valuemin='0' aria-valuemax='100' aria-label='\u9054\u6210\u7387'><div class='progress-fill' style='width: " + completionPercent + "%'></div></div><p>\u672a\u5b8c\u4e86\u306e\u4e88\u7b97\u5408\u8a08: " + String.format("%,d", pendingBudgetTotal) + "\u5186</p></section>";
                    html += "<details class='filters-accordion'" + (hasActiveFilters ? " open" : "") + "><summary>並び順・絞り込み" + (hasActiveFilters ? "（適用中）" : "") + "</summary><form class='category-filters' method='get' action='/'><label class='priority-sort'>並び順 <select name='sort' onchange='this.form.submit()'><option value=''>既定順</option><option value='priority'" + (sortByPriority ? " selected" : "") + ">優先度の高い順</option></select></label><span class='filter-title'>\u72b6\u614b\u3067\u7d5e\u308a\u8fbc\u307f</span><label class='category-filter'><input type='checkbox' name='status' value='done' " + (selectedStatuses.contains("done") ? "checked" : "") + " onchange='this.form.submit()'><span>\u9054\u6210</span></label><label class='category-filter'><input type='checkbox' name='status' value='pending' " + (selectedStatuses.contains("pending") ? "checked" : "") + " onchange='this.form.submit()'><span>\u672a\u9054\u6210</span></label><span class='filter-title'>\u30ab\u30c6\u30b4\u30ea\u30fc\u3067\u7d5e\u308a\u8fbc\u307f</span><a class='filter-clear' href='/'>\u3059\u3079\u3066</a>";
                    for (Category category : categories) html += "<label class='category-filter'><input type='checkbox' name='categoryId' value='" + category.id + "' " + (selectedCategoryIds.contains(String.valueOf(category.id)) ? "checked" : "") + " onchange='this.form.submit()'><span>" + escapeHtml(category.name) + "</span></label>";
                    html += "</form></details></div>" + buildTitleDialog(appTitle, session.csrfToken) + buildWishModal(session.csrfToken) + "<ul class='todo-list'>";
                    // カードに表示する連番を初期化します。
                    int number = 1;
                    for (Todo todo : todos) {
                        if (!selectedCategoryIds.isEmpty() && !selectedCategoryIds.contains(String.valueOf(todo.getCategoryId()))) continue;
                        String status = todo.isDone() ? "done" : "pending";
                        if (!selectedStatuses.isEmpty() && !selectedStatuses.contains(status)) continue;
                        // 達成済み項目の表示内容を作成します。
                        // 達成済み項目の表示内容を作成します。
                        String completedText = "";
                        if (todo.isDone()) {
                            // 達成済み項目の表示内容を準備します。
                            completedText = "<br>\u9054\u6210\u65e5: " + todo.getCompletedDate() + (todo.getCompletedAmount().isEmpty() ? "" : "<br>\u5b9f\u969b\u306e\u91d1\u984d: " + String.format("%,d", Long.parseLong(todo.getCompletedAmount())) + "\u5186");
                        }
                        // 予算の表示文字列を作成します。
                        String budgetText = todo.getBudget().isEmpty() ? "\u672a\u8a2d\u5b9a" : String.format("%,d", Long.parseLong(todo.getBudget())) + "\u5186";
                        String backgroundUrl = getBackgroundUrl(todo);
html += "<li class='note " + (todo.isDone() ? "done" : "pending") + "' style=\"background-image: linear-gradient(0deg, rgba(25,20,35,.18), rgba(25,20,35,.04)), url('" + backgroundUrl + "')\"><div class='note-panel'><div class='note-heading'><h2 class='note-title'>" + number + ". " + escapeHtml(todo.getTitle()) + "</h2>" + (todo.isDone() ? "<span class='done-mark'>✓ 達成！</span>" : "") + "</div><span class='wish-priority-display' aria-label='優先度 " + todo.getPriority() + "：" + priorityLabel(todo.getPriority()) + "'>" + priorityStars(todo.getPriority()) + "</span><span class='category-label'>" + escapeHtml(todo.getCategoryName()) + "</span><div class='note-details'>予定日: " + escapeHtml(todo.getDueDate()) + "<br>予算: " + budgetText + completedText + "</div><div class='note-actions'><span class='note-id'>ID " + todo.getId() + "</span><div class='action-icons'><button type='button' title='編集' aria-label='編集' class='delete-icon edit-icon' data-edit-id='" + todo.getId() + "' data-title='" + escapeHtml(todo.getTitle()) + "' data-category-id='" + todo.getCategoryId() + "' data-due-date='" + escapeHtml(todo.getDueDate()) + "' data-budget='" + escapeHtml(todo.getBudget()) + "' data-completed-date='" + escapeHtml(todo.getCompletedDate()) + "' data-completed-amount='" + escapeHtml(todo.getCompletedAmount()) + "' data-image-url='" + escapeHtml(getBackgroundUrl(todo)) + "' data-custom-image='" + (todo.getImagePath() != null ? "true" : "false") + "' data-location-name='" + escapeHtml(todo.getLocationName()) + "' data-address='" + escapeHtml(todo.getAddress()) + "' data-latitude='" + escapeHtml(todo.getLatitude()) + "' data-longitude='" + escapeHtml(todo.getLongitude()) + "' data-memo='" + escapeHtml(todo.getMemo()) + "' data-priority='" + todo.getPriority() + "'><img src='/assets/edit.png' alt='' class='icon-image'></button><form action='/delete' method='post' onsubmit=\"return confirm('この項目を削除してもよろしいですか？');\"><input type='hidden' name='_csrf' value='" + session.csrfToken + "'><input type='hidden' name='id' value='" + todo.getId() + "'><button type='submit' title='削除' aria-label='削除' class='delete-icon'><img src='/assets/trash.png' alt='' class='icon-image'></button></form></div></div></div></li>";
                        number++;
                    }
                    // カード一覧のHTMLを閉じます。
                    html += "</ul></div>";
                    // HTMLをUTF-8のバイト列に変換します。
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                    byte[] responseBody = html.getBytes("UTF-8");
                    // HTTP応答を送信します。
                    exchange.sendResponseHeaders(200, responseBody.length);
                    // 応答本文を送信します。
                    exchange.getResponseBody().write(responseBody);
                    // HTTP接続を閉じます。
                    exchange.getResponseBody().close();
                // 未対応のパスに対する応答を作成します。
                } else {
                    // ページが見つからない旨を設定します。
                    String message = "\u30da\u30fc\u30b8\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093";
                    // 応答の文字コードを指定します。
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    // HTMLをUTF-8のバイト列に変換します。
                    byte[] responseBody = message.getBytes("UTF-8");
                    // HTTP応答を送信します。
                    exchange.sendResponseHeaders(200, responseBody.length);
                    // 応答本文を送信します。
                    exchange.getResponseBody().write(responseBody);
                    // HTTP接続を閉じます。
                    exchange.getResponseBody().close();
                }
            } catch (SQLException e) {
                throw new IOException(e);
            }
        });
        // カレンダー画像の配信先を登録します。
        server.createContext("/assets/app.css", exchange -> {
            byte[] content = Files.readAllBytes(Path.of("assets", "app.css"));
            exchange.getResponseHeaders().set("Content-Type", "text/css; charset=UTF-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.createContext("/assets/app.js", exchange -> {
            byte[] content = Files.readAllBytes(Path.of("assets", "app.js"));
            exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });        server.createContext("/assets/calendar.png", exchange -> {
            byte[] icon = Files.readAllBytes(Path.of("assets/calendar.png"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, icon.length);
            exchange.getResponseBody().write(icon);
            exchange.close();
        });
        // 削除アイコン画像の配信先を登録します。
        server.createContext("/assets/edit.png", exchange -> {
            byte[] icon = Files.readAllBytes(Path.of("assets/edit.png"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, icon.length);
            exchange.getResponseBody().write(icon);
            exchange.close();
        });
        server.createContext("/assets/trash.png", exchange -> {
            byte[] icon = Files.readAllBytes(Path.of("assets/trash.png"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, icon.length);
            exchange.getResponseBody().write(icon);
            exchange.close();
        });
        // HTTPサーバーの待ち受けを開始します。
        server.start();
        // サーバーの起動URLを表示します。
        System.out.println("Server started at http://localhost:" + SERVER_PORT);
    }

    static void ensureUserSchema() throws Exception {
        boolean previousAutoCommit = connection.getAutoCommit();
        int resolvedAdminId;
        String generatedPassword = null;
        try {
            connection.setAutoCommit(false);
            AccountManagement.ensureSchema();
            if (!hasTodoColumn("user_id")) {
                try (Statement migration = connection.createStatement()) {
                    migration.executeUpdate("ALTER TABLE todos ADD COLUMN user_id INTEGER REFERENCES users(id)");
                }
            }

            try (PreparedStatement findAdmin = connection.prepareStatement("SELECT id, role FROM users WHERE username = ?")) {
                findAdmin.setString(1, "admin");
                try (ResultSet row = findAdmin.executeQuery()) {
                    if (row.next()) {
                        if (!"admin".equals(row.getString("role"))) {
                            throw new SQLException("The reserved admin username is already assigned to a non-admin account.");
                        }
                        resolvedAdminId = row.getInt("id");
                    } else {
                        generatedPassword = generateBootstrapPassword();
                        byte[] salt = new byte[16];
                        new SecureRandom().nextBytes(salt);
                        String passwordHash = hashPassword(generatedPassword, salt);
                        try (PreparedStatement insertAdmin = connection.prepareStatement(
                                "INSERT INTO users (username, display_name, password_hash, role, is_active, list_title) VALUES (?, ?, ?, 'admin', 1, ?)")) {
                            insertAdmin.setString(1, "admin");
                            insertAdmin.setString(2, "\u521d\u671f\u7ba1\u7406\u8005");
                            insertAdmin.setString(3, passwordHash);
                            insertAdmin.setString(4, INITIAL_LIST_TITLE);
                            insertAdmin.executeUpdate();
                        }
                        try (Statement forceChange = connection.createStatement()) {
                            forceChange.executeUpdate("UPDATE users SET must_change_password = 1 WHERE username = 'admin'");
                        }
                        try (PreparedStatement getAdminId = connection.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                            getAdminId.setString(1, "admin");
                            try (ResultSet adminRow = getAdminId.executeQuery()) {
                                if (!adminRow.next()) throw new SQLException("Initial admin account was not created.");
                                resolvedAdminId = adminRow.getInt(1);
                            }
                        }
                    }
                }
            }

            try (PreparedStatement assignExisting = connection.prepareStatement("UPDATE todos SET user_id = ? WHERE user_id IS NULL")) {
                assignExisting.setInt(1, resolvedAdminId);
                assignExisting.executeUpdate();
            }
            try (Statement migration = connection.createStatement()) {
                migration.executeUpdate("CREATE INDEX IF NOT EXISTS todos_user_id_idx ON todos(user_id)");
                migration.executeUpdate("CREATE TABLE IF NOT EXISTS app_migrations (name TEXT PRIMARY KEY)");
                if (migration.executeUpdate("INSERT OR IGNORE INTO app_migrations(name) VALUES ('step9-list-title')") == 1
                        && Files.isRegularFile(APP_TITLE_FILE)) {
                    String legacyTitle = Files.readString(APP_TITLE_FILE, StandardCharsets.UTF_8).trim();
                    if (!legacyTitle.isEmpty()) try (PreparedStatement title = connection.prepareStatement("UPDATE users SET list_title = ? WHERE id = ?")) {
                        title.setString(1, legacyTitle); title.setInt(2, resolvedAdminId); title.executeUpdate();
                    }
                }
            }
            try (Statement check = connection.createStatement(); ResultSet violations = check.executeQuery("PRAGMA foreign_key_check")) {
                if (violations.next()) throw new SQLException("Foreign key validation failed for migrated data.");
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }

        if (generatedPassword != null) {
            System.out.println("Initial administrator account created.");
            System.out.println("Username: admin");
            System.out.println("One-time password: " + generatedPassword);
            System.out.println("Save this password in a password manager. It will not be shown again.");
        }
    }

    static boolean hasTodoColumn(String columnName) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
            while (columns.next()) if (columnName.equals(columns.getString("name"))) return true;
        }
        return false;
    }

    static String generateBootstrapPassword() {
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    static String hashPassword(String password, byte[] salt) throws Exception {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "pbkdf2-sha256$" + PBKDF2_ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }

    // SQLiteからTodo一覧を読み込む処理です。
    static List<Todo> loadTodos(int userId, boolean sortByPriority) throws SQLException {
        List<Todo> todos = new ArrayList<>();
        String order = sortByPriority ? "t.priority DESC, t.done ASC, t.id ASC" : "t.done ASC, t.id ASC";
        try (PreparedStatement statement = connection.prepareStatement("SELECT t.id, t.title, t.done, t.due_date, t.budget, t.completed_date, t.completed_amount, t.category_id, t.image_path, t.location_name, t.address, t.latitude, t.longitude, t.memo, t.priority, c.name AS category_name FROM todos t LEFT JOIN categories c ON c.id = t.category_id WHERE t.user_id = ? ORDER BY " + order)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String budget = result.getString("budget");
                    String completedAmount = result.getString("completed_amount");
                    Todo todo = new Todo(result.getInt("id"), result.getString("title"), result.getString("due_date"), budget == null ? "" : budget);
                    todo.setDone(result.getInt("done") != 0);
                    String storedCompletedDate = result.getString("completed_date");
                    todo.setCompletedDate(storedCompletedDate == null ? "" : storedCompletedDate);
                    todo.setCompletedAmount(completedAmount == null ? "" : completedAmount);
                    todo.setCategory(result.getInt("category_id"), result.getString("category_name") == null ? "\u305d\u306e\u4ed6" : result.getString("category_name"));
                    todo.setImagePath(result.getString("image_path"));
                    todo.setDetails(result.getString("location_name"), result.getString("address"),
                        result.getObject("latitude") == null ? null : result.getDouble("latitude"),
                        result.getObject("longitude") == null ? null : result.getDouble("longitude"),
                        result.getString("memo"), result.getInt("priority"));
                    todos.add(todo);
                }
            }
        }
        return todos;
    }

    static void loadCategories() throws SQLException {
        categories.clear();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM categories ORDER BY id")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) categories.add(new Category(result.getInt("id"), result.getString("name")));
            }
        }
    }

    static int getOtherCategoryId() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM categories WHERE name = '\u305d\u306e\u4ed6'")) {
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getInt("id") : categories.get(categories.size() - 1).id; }
        }
    }

    static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String getAppTitle(int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT list_title FROM users WHERE id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next() && row.getString(1) != null && !row.getString(1).isBlank()) return row.getString(1);
            }
        }
        return DEFAULT_APP_TITLE;
    }

    static void saveAppTitle(com.sun.net.httpserver.HttpExchange exchange, AuthSession session) throws IOException, SQLException {
        byte[] body;
        try { body = readLimited(exchange.getRequestBody(), 4096); }
        catch (IOException e) { sendText(exchange, 413, "\u30bf\u30a4\u30c8\u30eb\u304c\u9577\u3059\u304e\u307e\u3059"); return; }
        String requestBody = new String(body, StandardCharsets.UTF_8);
        if (!verifyCsrf(exchange, session, formValue(requestBody, "_csrf"))) return;
        String title = "";
        for (String pair : requestBody.split("&")) {
            if (pair.startsWith("title=")) {
                title = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8).trim();
                break;
            }
        }
        if (title.isEmpty() || title.length() > 80) { sendText(exchange, 400, "\u30bf\u30a4\u30c8\u30eb\u306f1\u301c80\u6587\u5b57\u3067\u5165\u529b\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET list_title = ? WHERE id = ?")) {
            statement.setString(1, title); statement.setInt(2, session.userId); statement.executeUpdate();
        }
        if ("XMLHttpRequest".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) {
            exchange.sendResponseHeaders(204, -1); exchange.close();
        } else redirectHome(exchange);
    }

    static String buildTitleDialog(String appTitle, String csrfToken) {
        return "<dialog id='titleDialog' class='title-dialog'><form id='titleForm'><input type='hidden' name='_csrf' value='" + csrfToken + "'><h2>\u30a2\u30d7\u30ea\u30bf\u30a4\u30c8\u30eb\u306e\u5909\u66f4</h2><label for='appTitleInput'>\u30bf\u30a4\u30c8\u30eb</label><input id='appTitleInput' name='title' value='" + escapeHtml(appTitle) + "' maxlength='80' required><div id='titleError' class='title-error' role='alert'></div><div class='title-dialog-actions'><button type='button' id='titleCancel' class='title-dialog-cancel'>\u30ad\u30e3\u30f3\u30bb\u30eb</button><button type='submit' id='titleSave' class='title-dialog-save'>\u4fdd\u5b58</button></div></form></dialog>";
    }
    static String buildWishModal(String csrfToken) {
        StringBuilder options = new StringBuilder();
        int otherId = 0;
        try { otherId = getOtherCategoryId(); } catch (SQLException ignored) { }
        for (Category category : categories) {
            options.append("<option value='").append(category.id).append("' data-bg='").append(escapeHtml(categoryBackgroundUrl(category.id))).append("'").append(category.id == otherId ? " selected" : "").append(">").append(escapeHtml(category.name)).append("</option>");
        }
        return "<dialog id='wishModal' class='wish-modal'><div class='wish-modal-inner'><div class='wish-modal-header'><h2 id='wishModalTitle'>\u65b0\u898f\u8ffd\u52a0</h2><button type='button' class='wish-close' id='wishClose' aria-label='\u9589\u3058\u308b'>&#215;</button></div><div id='wishError' class='form-error' role='alert'></div><form id='wishForm' class='wish-form' action='/save' method='post' enctype='multipart/form-data'><input type='hidden' name='_csrf' value='" + csrfToken + "'><input type='hidden' name='id' id='wishId'><label for='wishTitle'>\u30bf\u30a4\u30c8\u30eb</label><input id='wishTitle' name='title' required maxlength='500'><label for='wishCategory'>\u30ab\u30c6\u30b4\u30ea\u30fc</label><select id='wishCategory' name='categoryId'>" + options + "</select>" + buildWishDetailsFields() + "<label for='wishDueDate'>\u4e88\u5b9a\u65e5</label><input id='wishDueDate' type='date' name='dueDate'><label for='wishBudget'>\u4e88\u7b97</label><input id='wishBudget' type='number' name='budget' min='0' step='1'><label>\u30b5\u30e0\u30cd\u30a4\u30eb\u753b\u50cf\uff08JPEG\u3001PNG\u3001WebP\u30015MB\u307e\u3067\uff09</label><div id='wishPreview' class='wish-preview'></div><input id='wishImage' type='file' name='image' accept='image/jpeg,image/png,image/webp'><label id='removeImageRow' class='remove-image-row' hidden><input id='removeImage' type='checkbox' name='removeImage' value='on'> \u500b\u5225\u753b\u50cf\u3092\u89e3\u9664\u3057\u3066\u30ab\u30c6\u30b4\u30ea\u30fc\u753b\u50cf\u306b\u623b\u3059</label><section id='completionFields' class='completion-fields' hidden><label for='wishCompletedDate'>\u9054\u6210\u65e5</label><input id='wishCompletedDate' type='date' name='completedDate'><label for='wishCompletedAmount'>\u5b9f\u969b\u306e\u91d1\u984d\uff08\u4efb\u610f\uff09</label><input id='wishCompletedAmount' type='number' name='completedAmount' min='0' step='1'></section><div class='wish-buttons'><button type='button' class='wish-cancel' id='wishCancel'>\u30ad\u30e3\u30f3\u30bb\u30eb</button><button type='submit' class='wish-save' id='wishSave'>\u4fdd\u5b58</button></div></form></div></dialog>";
    }
    static String buildWishDetailsFields() {
        StringBuilder fields = new StringBuilder("<div class='wish-extra-fields'><label for='wishLocationName'>場所の名称（任意）</label><input id='wishLocationName' name='locationName' maxlength='120'><label for='wishAddress'>住所（任意）</label><input id='wishAddress' name='address' maxlength='500'><div class='wish-coordinate-fields'><div><label for='wishLatitude'>緯度（任意、-90〜90）</label><input id='wishLatitude' name='latitude' type='number' inputmode='decimal' min='-90' max='90' step='any'></div><div><label for='wishLongitude'>経度（任意、-180〜180）</label><input id='wishLongitude' name='longitude' type='number' inputmode='decimal' min='-180' max='180' step='any'></div></div><label for='wishMemo'>メモ（任意）</label><textarea id='wishMemo' name='memo' rows='4' maxlength='5000'></textarea><fieldset class='wish-priority'><legend>優先度</legend><div class='priority-stars'>");
        String[] labels = {"低い", "やや低い", "普通", "高い", "最優先"};
        for (int value = 1; value <= 5; value++) {
            fields.append("<label class='priority-star' data-value='").append(value).append("'><input type='radio' name='priority' value='").append(value).append("' aria-label='").append(labels[value - 1]).append("（").append(value).append("）'");
            if (value == 3) fields.append(" checked");
            fields.append("><span aria-hidden='true'>★</span></label>");
        }
        return fields.append("</div><p class='priority-help'>1：低い　2：やや低い　3：普通　4：高い　5：最優先</p></fieldset></div>").toString();
    }

    static String priorityStars(int value) {
        return "★".repeat(value) + "☆".repeat(5 - value);
    }

    static String priorityLabel(int value) {
        return new String[] {"低い", "やや低い", "普通", "高い", "最優先"}[value - 1];
    }

    static String categoryBackgroundUrl(int categoryId) {
        for (String extension : List.of("jpg", "png", "webp")) {
            Path image = CATEGORY_IMAGE_DIR.resolve(categoryId + "." + extension).normalize();
            if (image.startsWith(CATEGORY_IMAGE_DIR) && Files.isRegularFile(image)) return "/assets/categories/" + categoryId + "." + extension;
        }
        return "";
    }
    static void saveTodo(com.sun.net.httpserver.HttpExchange exchange, AuthSession session) throws IOException, SQLException {
        String requestType = exchange.getRequestHeaders().getFirst("Content-Type");
        Matcher boundary = requestType == null ? Pattern.compile("$").matcher("") : Pattern.compile("boundary=\"?([^;\"]+)\"?").matcher(requestType);
        if (requestType == null || !requestType.toLowerCase().startsWith("multipart/form-data") || !boundary.find()) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        byte[] body;
        try { body = readLimited(exchange.getRequestBody(), MAX_IMAGE_BYTES + 128 * 1024); }
        catch (IOException e) { sendText(exchange, 413, "\u753b\u50cf\u306f5MB\u4ee5\u4e0b\u306b\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        Map<String, byte[]> parts = parseMultipart(body, boundary.group(1));
        if (!verifyCsrf(exchange, session, partText(parts, "_csrf"))) return;
        String idText = partText(parts, "id"), title = partText(parts, "title");
        WishDetails details;
        try { details = WishDetails.parse(parts); }
        catch (IllegalArgumentException error) { sendText(exchange, 400, "位置情報、メモ、優先度の入力を確認してください。"); return; }
        String categoryText = partText(parts, "categoryId"), dueDate = partText(parts, "dueDate"), budgetText = partText(parts, "budget");
        boolean editing = idText != null && !idText.isBlank();
        int id = 0;
        if (editing) {
            if (!idText.matches("[0-9]{1,10}")) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
            try { id = Integer.parseInt(idText); } catch (NumberFormatException e) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        }
        if (title == null || title.trim().isEmpty() || title.length() > 500) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        int categoryId;
        try { categoryId = categoryText == null || categoryText.isBlank() ? getOtherCategoryId() : Integer.parseInt(categoryText); }
        catch (Exception e) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        if (categories.stream().noneMatch(c -> c.id == categoryId)) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        Long budget = parseOptionalAmount(budgetText);
        if (budgetText != null && !budgetText.isBlank() && budget == null) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        if (dueDate == null) dueDate = "";
        try { if (!dueDate.isBlank()) LocalDate.parse(dueDate); }
        catch (Exception e) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        String completedDate = editing ? partText(parts, "completedDate") : "";
        String amountText = editing ? partText(parts, "completedAmount") : "";
        if (completedDate == null) completedDate = "";
        Long completedAmount = parseOptionalAmount(amountText);
        if (amountText != null && !amountText.isBlank() && completedAmount == null) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        try { if (!completedDate.isBlank()) LocalDate.parse(completedDate); }
        catch (Exception e) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        if (completedDate.isBlank()) completedAmount = null;
        byte[] image = parts.get("image");
        if (image != null && image.length == 0) image = null;
        if (image != null && (image.length > MAX_IMAGE_BYTES || !validImageContent(body, image))) { sendText(exchange, image.length > MAX_IMAGE_BYTES ? 413 : 415, "Only JPEG, PNG, or WebP images up to 5MB are accepted."); return; }

        String oldPath = null;
        if (editing) {
            try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ? AND user_id = ?")) {
                select.setInt(1, id);
                select.setInt(2, session.userId);
                try (ResultSet row = select.executeQuery()) { if (!row.next()) { sendText(exchange, 404, "\u9805\u76ee\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093"); return; } oldPath = row.getString(1); }
            }
        }
        boolean removeImage = editing && "on".equals(partText(parts, "removeImage"));
        String newPath = editing ? oldPath : null;
        Path temp = null, destination = null;
        if (image != null) {
            String filename = UUID.randomUUID() + "." + detectImageExtension(image);
            destination = UPLOAD_DIR.resolve(filename).normalize();
            if (!destination.startsWith(UPLOAD_DIR)) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
            temp = Files.createTempFile(UPLOAD_DIR, "wish-", ".tmp");
            Files.write(temp, image);
            newPath = "uploads/wishes/" + filename;
        } else if (removeImage) newPath = null;
        boolean moved = false, transactionStarted = false;
        try {
            connection.setAutoCommit(false); transactionStarted = true;
            if (temp != null) { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE); moved = true; }
            boolean done = editing && !completedDate.isBlank();
            String sql = editing
                ? "UPDATE todos SET title=?, category_id=?, due_date=?, budget=?, done=?, completed_date=?, completed_amount=?, image_path=?, location_name=?, address=?, latitude=?, longitude=?, memo=?, priority=? WHERE id=? AND user_id=?"
                : "INSERT INTO todos (title, done, due_date, budget, completed_date, completed_amount, category_id, image_path, user_id, location_name, address, latitude, longitude, memo, priority) VALUES (?,0,?,?,NULL,NULL,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title.trim());
                if (editing) {
                    statement.setInt(2, categoryId); statement.setString(3, dueDate);
                    if (budget == null) statement.setNull(4, Types.INTEGER); else statement.setLong(4, budget);
                    statement.setInt(5, done ? 1 : 0);
                    if (done) statement.setString(6, completedDate); else statement.setNull(6, Types.VARCHAR);
                    if (completedAmount == null) statement.setNull(7, Types.INTEGER); else statement.setLong(7, completedAmount);
                    if (newPath == null) statement.setNull(8, Types.VARCHAR); else statement.setString(8, newPath);
                    details.bind(statement, 9);
                    statement.setInt(15, id);
                    statement.setInt(16, session.userId);
                    if (statement.executeUpdate() != 1) throw new SQLException("item update failed");
                } else {
                    statement.setString(2, dueDate);
                    if (budget == null) statement.setNull(3, Types.INTEGER); else statement.setLong(3, budget);
                    statement.setInt(4, categoryId);
                    if (newPath == null) statement.setNull(5, Types.VARCHAR); else statement.setString(5, newPath);
                    statement.setInt(6, session.userId);
                    details.bind(statement, 7);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (Exception e) {
            if (transactionStarted) connection.rollback();
            if (moved) Files.deleteIfExists(destination);
            sendText(exchange, 500, "\u4fdd\u5b58\u3067\u304d\u307e\u305b\u3093\u3067\u3057\u305f");
            return;
        } finally {
            if (transactionStarted) connection.setAutoCommit(true);
            if (temp != null) Files.deleteIfExists(temp);
        }
        if (editing && (image != null || removeImage)) deleteStoredImage(oldPath);
        if ("XMLHttpRequest".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) {
            exchange.sendResponseHeaders(204, -1); exchange.close();
        } else redirectHome(exchange);
    }
    static String partText(Map<String, byte[]> parts, String name) { byte[] value = parts.get(name); return value == null ? null : new String(value, StandardCharsets.UTF_8); }
    static Long parseOptionalAmount(String value) { if (value == null || value.isBlank()) return null; try { long amount = Long.parseLong(value); return amount < 0 ? null : amount; } catch (NumberFormatException e) { return null; } }
    static boolean validImageContent(byte[] body, byte[] image) {
        String extension = detectImageExtension(image); if (extension == null) return false;
        String headers = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile("name=\"image\"; filename=\"[^\"]*\"\\r\\nContent-Type:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE).matcher(headers);
        if (!matcher.find()) return false;
        String mime = matcher.group(1).trim().toLowerCase();
        return extension.equals("jpg") ? mime.equals("image/jpeg") : mime.equals("image/" + extension);
    }

    static void showLoginPage(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        AuthSession current = getSession(exchange);
        if (current != null && current.userId > 0 && isAccountActive(current.userId)) { redirectHome(exchange); return; }
        if (current == null || current.userId > 0) current = createSession(0, "", ANONYMOUS_SESSION_TIMEOUT_MS);
        setSessionCookie(exchange, current, ANONYMOUS_SESSION_TIMEOUT_MS);
        sendHtml(exchange, loginHtml(current.csrfToken, ""));
    }

    static void handleLogin(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        if (PRODUCTION_MODE && !isSecureTransport(exchange)) { sendText(exchange, 426, "HTTPS is required."); return; }
        byte[] raw;
        try { raw = readLimited(exchange.getRequestBody(), 8192); }
        catch (IOException e) { sendText(exchange, 413, "Request too large."); return; }
        Map<String, String> fields = parseForm(new String(raw, StandardCharsets.UTF_8));
        AuthSession anonymous = getSession(exchange);
        if (anonymous == null || anonymous.userId > 0) { sendText(exchange, 403, "Login form expired."); return; }
        if (!csrfMatches(anonymous, fields.get("_csrf"))) {
            SESSIONS.remove(sessionKeyFromCookie(exchange));
            AuthSession refreshed = createSession(0, "", ANONYMOUS_SESSION_TIMEOUT_MS);
            setSessionCookie(exchange, refreshed, ANONYMOUS_SESSION_TIMEOUT_MS);
            sendHtmlStatus(exchange, 200, loginHtml(refreshed.csrfToken, "ログイン画面を更新しました。ユーザーIDとパスワードをもう一度入力してください。"));
            return;
        }
        String username = fields.getOrDefault("username", "").trim();
        String password = fields.getOrDefault("password", "");
        if (username.length() > 100 || password.length() > 1024) { loginFailed(exchange, anonymous, username); return; }
        String attemptKey = loginAttemptKey(exchange, username);
        if (isRateLimited(attemptKey)) { sendHtmlStatus(exchange, 429, loginHtml(anonymous.csrfToken, "試行回数が上限に達しました。15分後に再度お試しください。")); return; }
        int userId = 0;
        String role = "";
        boolean valid = false;
        try (PreparedStatement query = connection.prepareStatement("SELECT id, password_hash, role, is_active FROM users WHERE username = ?")) {
            query.setString(1, username);
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    userId = row.getInt("id"); role = row.getString("role");
                    boolean passwordMatches = verifyPassword(password, row.getString("password_hash"));
                    valid = row.getInt("is_active") == 1 && passwordMatches;
                } else {
                    // Keep the unknown-user path close to the normal PBKDF2 cost.
                    hashPassword(password, new byte[16]);
                }
            }
        } catch (Exception e) { throw new IOException("Password verification failed", e); }
        if (!valid) { loginFailed(exchange, anonymous, username); return; }
        SESSIONS.remove(sessionKeyFromCookie(exchange));
        AuthSession authenticated = createSession(userId, role, SESSION_IDLE_TIMEOUT_MS);
        setSessionCookie(exchange, authenticated, SESSION_IDLE_TIMEOUT_MS);
        synchronized (LOGIN_ATTEMPTS) { LOGIN_ATTEMPTS.remove(attemptKey); }
        redirectHome(exchange);
    }

    static void loginFailed(com.sun.net.httpserver.HttpExchange exchange, AuthSession oldSession, String username) throws IOException {
        if (oldSession != null && oldSession.rawToken != null) SESSIONS.remove(sha256(oldSession.rawToken));
        String key = loginAttemptKey(exchange, username);
        recordLoginFailure(key);
        AuthSession next = createSession(0, "", ANONYMOUS_SESSION_TIMEOUT_MS);
        setSessionCookie(exchange, next, ANONYMOUS_SESSION_TIMEOUT_MS);
        sendHtmlStatus(exchange, isRateLimited(key) ? 429 : 401, loginHtml(next.csrfToken, isRateLimited(key)
            ? "試行回数が上限に達しました。15分後に再度お試しください。"
            : "ユーザーIDまたはパスワードを確認してください。"));
    }

    static void handleLogout(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        AuthSession session = requireAuthenticated(exchange);
        if (session == null) return;
        byte[] raw;
        try { raw = readLimited(exchange.getRequestBody(), 4096); }
        catch (IOException e) { sendText(exchange, 413, "Request too large."); return; }
        if (!verifyCsrf(exchange, session, formValue(new String(raw, StandardCharsets.UTF_8), "_csrf"))) return;
        SESSIONS.remove(sessionKeyFromCookie(exchange));
        clearSessionCookie(exchange);
        exchange.getResponseHeaders().set("Location", "/login"); exchange.sendResponseHeaders(303, -1); exchange.close();
    }

    static AuthSession requireAuthenticated(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        if (PRODUCTION_MODE && !isSecureTransport(exchange)) { sendText(exchange, 426, "HTTPS is required."); return null; }
        AuthSession session = getSession(exchange);
        // Auxiliary requests must not invalidate the session used by the login form.
        if (session == null || session.userId <= 0) { exchange.getResponseHeaders().set("Location", "/login"); exchange.sendResponseHeaders(303, -1); exchange.close(); return null; }
        long now = System.currentTimeMillis();
        if (now - session.lastSeenAt > SESSION_IDLE_TIMEOUT_MS || now - session.createdAt > SESSION_ABSOLUTE_TIMEOUT_MS || !isAccountActive(session.userId)) {
            SESSIONS.remove(sessionKeyFromCookie(exchange)); clearSessionCookie(exchange);
            exchange.getResponseHeaders().set("Location", "/login"); exchange.sendResponseHeaders(303, -1); exchange.close(); return null;
        }
        session.lastSeenAt = now;
        setSessionCookie(exchange, session, SESSION_IDLE_TIMEOUT_MS);
        String path = exchange.getRequestURI().getPath();
        if (AccountManagement.mustChangePassword(session.userId)
                && !path.equals("/account/password") && !path.equals("/logout")) {
            AccountManagement.redirect(exchange, "/account/password");
            return null;
        }
        return session;
    }

    static AuthSession getSession(com.sun.net.httpserver.HttpExchange exchange) {
        String key = sessionKeyFromCookie(exchange);
        if (key == null) return null;
        AuthSession session = SESSIONS.get(key);
        if (session != null && session.userId == 0 && System.currentTimeMillis() - session.lastSeenAt > ANONYMOUS_SESSION_TIMEOUT_MS) { SESSIONS.remove(key); return null; }
        return session;
    }

    static AuthSession createSession(int userId, String role, long lifetime) {
        byte[] token = new byte[32], csrf = new byte[32]; SecureRandom random = new SecureRandom(); random.nextBytes(token); random.nextBytes(csrf);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        String csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(csrf);
        long now = System.currentTimeMillis(); AuthSession session = new AuthSession(userId, role, csrfToken, now, now);
        SESSIONS.put(sha256(rawToken), session);
        session.rawToken = rawToken;
        return session;
    }

    static String sessionKeyFromCookie(com.sun.net.httpserver.HttpExchange exchange) {
        String cookies = exchange.getRequestHeaders().getFirst("Cookie"); if (cookies == null) return null;
        for (String cookie : cookies.split(";")) { String[] pair = cookie.trim().split("=", 2); if (pair.length == 2 && SESSION_COOKIE.equals(pair[0]) && pair[1].matches("[A-Za-z0-9_-]{40,50}")) return sha256(pair[1]); }
        return null;
    }

    static void setSessionCookie(com.sun.net.httpserver.HttpExchange exchange, AuthSession session, long maxAgeMs) {
        String value = SESSION_COOKIE + "=" + session.rawToken + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + Math.max(1, maxAgeMs / 1000) + (isSecureTransport(exchange) || HTTPS_TERMINATED ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", value);
    }

    static void clearSessionCookie(com.sun.net.httpserver.HttpExchange exchange) {
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (isSecureTransport(exchange) || HTTPS_TERMINATED ? "; Secure" : ""));
    }

    static boolean verifyCsrf(com.sun.net.httpserver.HttpExchange exchange, AuthSession session, String supplied) throws IOException {
        if (!csrfMatches(session, supplied)) { sendText(exchange, 403, "画面の有効期限が切れたか、セッションが更新されています。ページを再読み込みして、もう一度お試しください。"); return false; }
        return true;
    }

    static boolean csrfMatches(AuthSession session, String supplied) {
        return session != null && supplied != null && MessageDigest.isEqual(session.csrfToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    static boolean verifyPassword(String password, String stored) throws Exception {
        if (stored == null) return false;
        String[] pieces = stored.split("\\$", -1);
        if (pieces.length != 4 || !"pbkdf2-sha256".equals(pieces[0])) return false;
        int iterations; byte[] salt, expected;
        try { iterations = Integer.parseInt(pieces[1]); salt = Base64.getUrlDecoder().decode(pieces[2]); expected = Base64.getUrlDecoder().decode(pieces[3]); }
        catch (IllegalArgumentException e) { return false; }
        if (iterations < 10_000 || iterations > 1_000_000 || salt.length < 16 || expected.length != 32) return false;
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        try { byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return MessageDigest.isEqual(expected, actual); }
        finally { spec.clearPassword(); java.util.Arrays.fill(chars, '\0'); }
    }

    static boolean isAccountActive(int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT is_active FROM users WHERE id = ?")) { statement.setInt(1, userId); try (ResultSet row = statement.executeQuery()) { return row.next() && row.getInt(1) == 1; } }
    }

    static boolean isSecureTransport(com.sun.net.httpserver.HttpExchange exchange) {
        return exchange instanceof com.sun.net.httpserver.HttpsExchange || (HTTPS_TERMINATED && "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto")));
    }

    static String loginAttemptKey(com.sun.net.httpserver.HttpExchange exchange, String username) {
        String ip = exchange.getRemoteAddress() == null ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
        return sha256((ip + "\n" + (username == null ? "" : username.toLowerCase(java.util.Locale.ROOT))));
    }

    static void recordLoginFailure(String key) {
        long now = System.currentTimeMillis(); synchronized (LOGIN_ATTEMPTS) {
            LoginAttempt attempt = LOGIN_ATTEMPTS.get(key);
            if (attempt == null || now - attempt.windowStart >= LOGIN_WINDOW_MS) LOGIN_ATTEMPTS.put(key, new LoginAttempt(now, 1)); else attempt.failures++;
            if (LOGIN_ATTEMPTS.size() > 10_000) LOGIN_ATTEMPTS.entrySet().removeIf(e -> now - e.getValue().windowStart > LOGIN_WINDOW_MS);
        }
    }

    static boolean isRateLimited(String key) {
        long now = System.currentTimeMillis(); synchronized (LOGIN_ATTEMPTS) { LoginAttempt a = LOGIN_ATTEMPTS.get(key); return a != null && now - a.windowStart < LOGIN_WINDOW_MS && a.failures >= MAX_LOGIN_FAILURES; }
    }

    static String sha256(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> values = new HashMap<>(); for (String entry : body.split("&")) { int eq = entry.indexOf('='); if (eq >= 0) values.put(URLDecoder.decode(entry.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(entry.substring(eq + 1), StandardCharsets.UTF_8)); } return values;
    }

    static String loginHtml(String csrf, String error) {
        return "<!doctype html><html lang='ja'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>ログイン</title><link rel='stylesheet' href='/assets/app.css'></head><body class='login-page'><main class='login-card'><h1>バケットリスト</h1><h2>ログイン</h2>" + (error.isEmpty() ? "" : "<p class='login-error' role='alert'>" + escapeHtml(error) + "</p>") + "<form method='post' action='/login'><input type='hidden' name='_csrf' value='" + csrf + "'><label for='username'>ユーザーID</label><input id='username' name='username' autocomplete='username' maxlength='100' required autofocus><label for='password'>パスワード</label><input id='password' name='password' type='password' autocomplete='current-password' maxlength='1024' required><button type='submit'>ログイン</button></form></main></body></html>";
    }

    static void sendHtmlStatus(com.sun.net.httpserver.HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }

    static void deleteTodo(com.sun.net.httpserver.HttpExchange exchange, AuthSession session) throws IOException, SQLException {
        byte[] raw; try { raw = readLimited(exchange.getRequestBody(), 4096); } catch (IOException e) { sendText(exchange, 413, "Request too large."); return; }
        Map<String, String> form = parseForm(new String(raw, StandardCharsets.UTF_8));
        if (!verifyCsrf(exchange, session, form.get("_csrf"))) return;
        int id; try { id = Integer.parseInt(form.getOrDefault("id", "")); if (id < 1) throw new NumberFormatException(); } catch (NumberFormatException e) { sendText(exchange, 400, "Invalid item."); return; }
        String oldImagePath = null;
        try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ? AND user_id = ?")) {
            select.setInt(1, id); select.setInt(2, session.userId);
            try (ResultSet row = select.executeQuery()) { if (row.next()) oldImagePath = row.getString(1); else { sendText(exchange, 404, "項目が見つかりません"); return; } }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM todos WHERE id = ? AND user_id = ?")) {
            delete.setInt(1, id); delete.setInt(2, session.userId);
            if (delete.executeUpdate() != 1) { sendText(exchange, 404, "項目が見つかりません"); return; }
        }
        deleteStoredImage(oldImagePath); redirectHome(exchange);
    }

    static void sendHtml(com.sun.net.httpserver.HttpExchange exchange, String html) throws IOException {
        byte[] response = html.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
    }

    static String getBackgroundUrl(Todo todo) {
        String imagePath = todo.getImagePath();
        if (imagePath != null && imagePath.matches("uploads/wishes/[a-f0-9-]+\\.(jpg|png|webp)")) return "/" + imagePath;
        for (String extension : List.of("jpg", "png", "webp")) {
            Path categoryImage = CATEGORY_IMAGE_DIR.resolve(todo.getCategoryId() + "." + extension).normalize();
            if (categoryImage.startsWith(CATEGORY_IMAGE_DIR) && Files.isRegularFile(categoryImage)) return "/assets/categories/" + todo.getCategoryId() + "." + extension;
        }
        return "";
    }

    static void serveWishImage(com.sun.net.httpserver.HttpExchange exchange, AuthSession session, String filename) throws IOException, SQLException {
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendText(exchange, 405, "Method not allowed."); return;
        }
        if (!filename.matches("[a-f0-9-]+\\.(jpg|png|webp)")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM todos WHERE image_path = ? AND user_id = ?")) {
            statement.setString(1, "uploads/wishes/" + filename); statement.setInt(2, session.userId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            }
        }
        serveImage(exchange, UPLOAD_DIR, filename);
    }

    static void serveImage(com.sun.net.httpserver.HttpExchange exchange, Path base, String filename) throws IOException {
        if (!filename.matches("[a-zA-Z0-9_-]+\\.(jpg|png|webp)")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_IMAGE_BYTES) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        String type = filename.endsWith(".jpg") ? "image/jpeg" : filename.endsWith(".png") ? "image/png" : "image/webp";
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, Files.size(file));
        Files.copy(file, exchange.getResponseBody());
        exchange.close();
    }

    static Map<String, byte[]> parseMultipart(byte[] body, String boundary) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int cursor = 0;
        while ((cursor = indexOf(body, delimiter, cursor)) >= 0) {
            cursor += delimiter.length;
            if (cursor + 2 <= body.length && body[cursor] == '-' && body[cursor + 1] == '-') break;
            if (cursor + 2 <= body.length && body[cursor] == '\r' && body[cursor + 1] == '\n') cursor += 2;
            int headerEnd = indexOf(body, separator, cursor); if (headerEnd < 0) break;
            String headers = new String(body, cursor, headerEnd - cursor, StandardCharsets.ISO_8859_1);
            Matcher name = Pattern.compile("Content-Disposition:.*?name=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(headers);
            int valueStart = headerEnd + separator.length;
            int valueEnd = indexOf(body, delimiter, valueStart); if (valueEnd < 0) break;
            if (name.find()) parts.put(name.group(1), java.util.Arrays.copyOfRange(body, valueStart, Math.max(valueStart, valueEnd - 2)));
            cursor = valueEnd;
        }
        return parts;
    }

    static int indexOf(byte[] data, byte[] needle, int from) {
        outer: for (int i = Math.max(0, from); i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static String detectImageExtension(byte[] data) {
        if (data.length >= 3 && (data[0] & 255) == 0xff && (data[1] & 255) == 0xd8 && (data[2] & 255) == 0xff) return "jpg";
        if (data.length >= 8 && (data[0] & 255) == 0x89 && data[1] == 0x50 && data[2] == 0x4e && data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a && data[6] == 0x1a && data[7] == 0x0a) return "png";
        if (data.length >= 12 && new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "webp";
        return null;
    }

    static byte[] readLimited(java.io.InputStream input, long limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) >= 0) { if (output.size() + count > limit) throw new IOException("request too large"); output.write(buffer, 0, count); }
        return output.toByteArray();
    }

    static String formValue(String body, String key) {
        for (String entry : body.split("&")) if (entry.startsWith(key + "=")) return URLDecoder.decode(entry.substring(key.length() + 1), StandardCharsets.UTF_8);
        return null;
    }

    // Call only with a previous image path selected through an owner-scoped Wish query.
    static void deleteStoredImage(String relative) throws IOException, SQLException {
        if (relative == null || !relative.matches("uploads/wishes/[a-f0-9-]+\\.(jpg|png|webp)")) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM todos WHERE image_path = ? LIMIT 1")) {
            statement.setString(1, relative);
            try (ResultSet row = statement.executeQuery()) { if (row.next()) return; }
        }
        Path file = Path.of(relative).toAbsolutePath().normalize(); if (file.startsWith(UPLOAD_DIR)) Files.deleteIfExists(file);
    }

    static void sendText(com.sun.net.httpserver.HttpExchange exchange, int status, String message) throws IOException {
        byte[] response = message.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); exchange.close();
    }

    static void redirectHome(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/"); exchange.sendResponseHeaders(303, -1); exchange.close();
    }
}

class AuthSession {
    final int userId;
    final String role;
    final String csrfToken;
    final long createdAt;
    volatile long lastSeenAt;
    String rawToken;
    AuthSession(int userId, String role, String csrfToken, long createdAt, long lastSeenAt) { this.userId = userId; this.role = role; this.csrfToken = csrfToken; this.createdAt = createdAt; this.lastSeenAt = lastSeenAt; }
}

class LoginAttempt {
    final long windowStart;
    int failures;
    LoginAttempt(long windowStart, int failures) { this.windowStart = windowStart; this.failures = failures; }
}

class Category {
    final int id;
    final String name;
    Category(int id, String name) { this.id = id; this.name = name; }
}

// Todoの情報と状態を管理するクラスです。
class Todo {
    // TodoのIDを保持します。
    private final int id;
    // Todoのタイトルを保持します。
    private final String title;
    // 達成状態を保持します。
    private boolean done;
    // 予定日を保持します。
    private final String dueDate;
    // 予算を保持します。
    private final String budget;
    // 達成日を保持します。
    private String completedDate = "";
    // 実際の金額を保持します。
    private String completedAmount = "";
    private int categoryId;
    private String imagePath;
    private String locationName;
    private String address;
    private Double latitude;
    private Double longitude;
    private String memo;
    private int priority = 3;
    private String categoryName = "\u305d\u306e\u4ed6";

    // 各項目の初期値を設定します。
    Todo(int id, String title, String dueDate, String budget) {
        this.id = id;
        this.title = title;
        this.dueDate = dueDate;
        this.budget = budget;
        this.done = false;
    }

    // TodoのIDを返します。
    int getId() {
        return id;
    }

    // Todoのタイトルを返します。
    String getTitle() {
        return title;
    }

    // 達成状態を返します。
    boolean isDone() {
        return done;
    }

    // 達成状態を設定します。
    void setDone(boolean done) {
        this.done = done;
    }

    // 予定日を返します。
    String getDueDate() {
        return dueDate;
    }

    // 予算を返します。
    String getBudget() {
        return budget;
    }

    // 達成日を返します。
    String getCompletedDate() {
        return completedDate;
    }

    // 達成日を設定します。
    void setCompletedDate(String completedDate) {
        this.completedDate = completedDate;
    }

    // 実際の金額を返します。
    String getCompletedAmount() {
        return completedAmount;
    }

    int getCategoryId() { return categoryId; }
    String getImagePath() { return imagePath; }
    void setImagePath(String imagePath) { this.imagePath = imagePath; }
    String getCategoryName() { return categoryName; }
    void setCategory(int categoryId, String categoryName) { this.categoryId = categoryId; this.categoryName = categoryName; }
    String getLocationName() { return locationName == null ? "" : locationName; }
    String getAddress() { return address == null ? "" : address; }
    String getLatitude() { return latitude == null ? "" : latitude.toString(); }
    String getLongitude() { return longitude == null ? "" : longitude.toString(); }
    String getMemo() { return memo == null ? "" : memo; }
    int getPriority() { return priority; }
    void setDetails(String locationName, String address, Double latitude, Double longitude, String memo, int priority) {
        this.locationName = locationName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.memo = memo;
        this.priority = priority;
    }

    // 実際の金額を設定します。
    void setCompletedAmount(String completedAmount) {
        this.completedAmount = completedAmount;
    }
}
