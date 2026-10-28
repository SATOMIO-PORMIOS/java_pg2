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

// アプリケーション本体です。
public class App {
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final String DEFAULT_APP_TITLE = "\u3084\u308a\u305f\u3044\u3053\u3068\u30ea\u30b9\u30c8";
    static final Path APP_TITLE_FILE = Path.of("app-title.txt").toAbsolutePath().normalize();
    static final Path UPLOAD_DIR = Path.of("uploads", "wishes").toAbsolutePath().normalize();
    static final Path CATEGORY_IMAGE_DIR = Path.of("assets", "categories").toAbsolutePath().normalize();
    // 画面表示に使うTodo一覧を保持します。
    static List<Todo> todos = new ArrayList<>();
    static List<Category> categories = new ArrayList<>();
    // SQLiteへの接続を保持します。
    static Connection connection;

    // データベースを準備し、HTTPサーバーを起動します。
    public static void main(String[] args) throws Exception {
        // SQLiteデータベースに接続します。
        connection = DriverManager.getConnection("jdbc:sqlite:todos.db");
        // テーブルを作成し、必要なデータ移行を行います。
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER NOT NULL, due_date TEXT, budget INTEGER, completed_date TEXT, completed_amount INTEGER)");
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
        Files.createDirectories(UPLOAD_DIR);
        Files.createDirectories(CATEGORY_IMAGE_DIR);
        loadCategories();
        // SQLiteからTodo一覧を読み込みます。
        loadTodos();
        // HTTPサーバーを作成します。
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // HTTPリクエストの処理先を登録します。
        server.createContext("/", exchange -> {
            try {
                // SQLiteからTodo一覧を読み込みます。
                loadTodos();
                // リクエストされたURLのパスを取得します。
                String path = exchange.getRequestURI().getPath();
                // HTTPリクエストのメソッドを取得します。
                String method = exchange.getRequestMethod();
                if (path.startsWith("/uploads/wishes/")) {
                    serveImage(exchange, UPLOAD_DIR, path.substring("/uploads/wishes/".length()));
                    return;
                } else if (path.startsWith("/assets/categories/")) {
                    serveImage(exchange, CATEGORY_IMAGE_DIR, path.substring("/assets/categories/".length()));
                    return;
                }
                // Todo新規登録のPOSTリクエストを処理します。
                if (path.equals("/settings/title") && method.equals("POST")) {
                    saveAppTitle(exchange);
                    return;
                } else if ((path.equals("/save") || path.equals("/edit")) && method.equals("POST")) {
                    saveTodo(exchange);
                    return;
                } else if (path.equals("/edit") && method.equals("GET")) {
                    redirectHome(exchange);
                    return;
                } else if (path.equals("/delete") && method.equals("GET")) {
                    // URLのクエリ文字列を取得します。
                    String query = exchange.getRequestURI().getQuery();
                    // クエリに有効なIDが含まれているか確認します。
                    if (query != null && query.startsWith("id=") && query.length() > 3) {
                        // クエリから項目IDを解析します。
                        try {
                            int id = Integer.parseInt(query.substring(3));
                            String oldImagePath = null;
                            try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ?")) {
                                select.setInt(1, id);
                                try (ResultSet row = select.executeQuery()) { if (row.next()) oldImagePath = row.getString(1); }
                            }
                            // 指定したTodoをデータベースから削除します。
                            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM todos WHERE id = ?")) {
                                statement.setInt(1, id);
                                statement.executeUpdate();
                            }
                            deleteStoredImage(oldImagePath);
                        // IDが数値でない場合は更新を行いません。
                        } catch (NumberFormatException e) {
                        }
                    }
                    // 処理後に一覧画面へ戻します。
                    exchange.getResponseHeaders().set("Location", "/");
                    // HTTP応答を送信します。
                    exchange.sendResponseHeaders(303, -1);
                    // HTTP接続を閉じます。
                    exchange.close();
                    // 現在のリクエスト処理を終了します。
                    return;
                // 一覧画面を表示します。
                } else if (path.equals("/")) {
                    // 一覧画面のHTMLとスタイルを作成します。
                    String appTitle = getAppTitle();
                    String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>" + escapeHtml(appTitle) + "</title><link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Yusei+Magic&display=swap' rel='stylesheet'><link rel='stylesheet' href='/assets/app.css'><script src='/assets/app.js' defer></script></head><body><header class='app-header'><h1>" + escapeHtml(appTitle) + "</h1><button type='button' id='appTitleEdit' class='title-edit-button' title='\u30bf\u30a4\u30c8\u30eb\u3092\u5909\u66f4'><img src='/assets/edit.png' alt=''></button></header><button type='button' id='newWishButton' class='new-wish-button'>&#65291; &#26032;&#35215;&#36861;&#21152;</button>";
                    // Todoがない場合の案内を表示します。
                    // Todoがない場合の案内を表示します。
                    if (todos.isEmpty()) { html += "<p>\u307e\u3060\u9805\u76ee\u304c\u3042\u308a\u307e\u305b\u3093\u3002\u65b0\u898f\u8ffd\u52a0\u304b\u3089\u767b\u9332\u3057\u3066\u304f\u3060\u3055\u3044\u3002</p>"; }
                    // 達成数と未完了予算を集計します。
                    int completedCount = 0;
                    long pendingBudgetTotal = 0;
                    for (Todo todo : todos) {
                        if (todo.isDone()) { completedCount++; }
                        else if (!todo.getBudget().isEmpty()) { pendingBudgetTotal += Long.parseLong(todo.getBudget()); }
                    }
                    int completionPercent = todos.isEmpty() ? 0 : completedCount * 100 / todos.size();
                    String filterQuery = exchange.getRequestURI().getQuery();
                    List<String> selectedCategoryIds = new ArrayList<>();
                    List<String> selectedStatuses = new ArrayList<>();
                    if (filterQuery != null) for (String parameter : filterQuery.split("&")) {
                        if (parameter.startsWith("categoryId=")) selectedCategoryIds.add(URLDecoder.decode(parameter.substring(11), StandardCharsets.UTF_8));
                        else if (parameter.startsWith("status=")) selectedStatuses.add(URLDecoder.decode(parameter.substring(7), StandardCharsets.UTF_8));
                    }
                    html += "<div class='list-content'><section class='progress-summary' aria-label='\u9054\u6210\u7387'><div class='progress-label'><span>\u9054\u6210\u7387</span><span>" + completionPercent + "%</span></div><div class='progress-track' role='progressbar' aria-valuenow='" + completionPercent + "' aria-valuemin='0' aria-valuemax='100' aria-label='\u9054\u6210\u7387'><div class='progress-fill' style='width: " + completionPercent + "%'></div></div><p>\u672a\u5b8c\u4e86\u306e\u4e88\u7b97\u5408\u8a08: " + String.format("%,d", pendingBudgetTotal) + "\u5186</p></section>";
                    html += "<form class='category-filters' method='get' action='/'><span class='filter-title'>\u72b6\u614b\u3067\u7d5e\u308a\u8fbc\u307f</span><label class='category-filter'><input type='checkbox' name='status' value='done' " + (selectedStatuses.contains("done") ? "checked" : "") + " onchange='this.form.submit()'><span>\u9054\u6210</span></label><label class='category-filter'><input type='checkbox' name='status' value='pending' " + (selectedStatuses.contains("pending") ? "checked" : "") + " onchange='this.form.submit()'><span>\u672a\u9054\u6210</span></label><span class='filter-title'>\u30ab\u30c6\u30b4\u30ea\u30fc\u3067\u7d5e\u308a\u8fbc\u307f</span><a class='filter-clear' href='/'>\u3059\u3079\u3066</a>";
                    for (Category category : categories) html += "<label class='category-filter'><input type='checkbox' name='categoryId' value='" + category.id + "' " + (selectedCategoryIds.contains(String.valueOf(category.id)) ? "checked" : "") + " onchange='this.form.submit()'><span>" + escapeHtml(category.name) + "</span></label>";
                    html += "</form>" + buildTitleDialog(appTitle) + buildWishModal() + "<ul class='todo-list'>";
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
html += "<li class='note " + (todo.isDone() ? "done" : "pending") + "' style=\"background-image: linear-gradient(0deg, rgba(25,20,35,.18), rgba(25,20,35,.04)), url('" + backgroundUrl + "')\"><div class='note-panel'><div class='note-heading'><h2 class='note-title'>" + number + ". " + escapeHtml(todo.getTitle()) + "</h2>" + (todo.isDone() ? "<span class='done-mark'>✓ 達成！</span>" : "") + "</div><span class='category-label'>" + escapeHtml(todo.getCategoryName()) + "</span><div class='note-details'>予定日: " + escapeHtml(todo.getDueDate()) + "<br>予算: " + budgetText + completedText + "</div><div class='note-actions'><span class='note-id'>ID " + todo.getId() + "</span><div class='action-icons'><button type='button' title='編集' aria-label='編集' class='delete-icon edit-icon' data-edit-id='" + todo.getId() + "' data-title='" + escapeHtml(todo.getTitle()) + "' data-category-id='" + todo.getCategoryId() + "' data-due-date='" + escapeHtml(todo.getDueDate()) + "' data-budget='" + escapeHtml(todo.getBudget()) + "' data-completed-date='" + escapeHtml(todo.getCompletedDate()) + "' data-completed-amount='" + escapeHtml(todo.getCompletedAmount()) + "' data-image-url='" + escapeHtml(getBackgroundUrl(todo)) + "' data-custom-image='" + (todo.getImagePath() != null ? "true" : "false") + "'><img src='/assets/edit.png' alt='' class='icon-image'></button><a href='/delete?id=" + todo.getId() + "' title='削除' aria-label='削除' class='delete-icon' onclick=\"return confirm('この項目を削除してもよろしいですか？');\"><img src='/assets/trash.png' alt='' class='icon-image'></a></div></div></div></li>";
                        number++;
                    }
                    // カード一覧のHTMLを閉じます。
                    html += "</ul></div>";
                    // HTMLをUTF-8のバイト列に変換します。
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
        System.out.println("Server started at http://localhost:8080");
    }

    // SQLiteからTodo一覧を読み込む処理です。
    static void loadTodos() throws SQLException {
        todos.clear();
        try (PreparedStatement statement = connection.prepareStatement("SELECT t.id, t.title, t.done, t.due_date, t.budget, t.completed_date, t.completed_amount, t.category_id, t.image_path, c.name AS category_name FROM todos t LEFT JOIN categories c ON c.id = t.category_id ORDER BY t.done ASC, t.id ASC")) {
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
                    todos.add(todo);
                }
            }
        }
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

    static Todo findTodo(int id) {
        for (Todo todo : todos) if (todo.getId() == id) return todo;
        return null;
    }

    static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String getAppTitle() {
        try {
            if (Files.isRegularFile(APP_TITLE_FILE)) {
                String title = Files.readString(APP_TITLE_FILE, StandardCharsets.UTF_8).trim();
                if (!title.isEmpty()) return title;
            }
        } catch (IOException ignored) { }
        return DEFAULT_APP_TITLE;
    }

    static void saveAppTitle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] body;
        try { body = readLimited(exchange.getRequestBody(), 4096); }
        catch (IOException e) { sendText(exchange, 413, "\u30bf\u30a4\u30c8\u30eb\u304c\u9577\u3059\u304e\u307e\u3059"); return; }
        String title = "";
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (pair.startsWith("title=")) {
                title = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8).trim();
                break;
            }
        }
        if (title.isEmpty() || title.length() > 80) { sendText(exchange, 400, "\u30bf\u30a4\u30c8\u30eb\u306f1\u301c80\u6587\u5b57\u3067\u5165\u529b\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        Files.writeString(APP_TITLE_FILE, title, StandardCharsets.UTF_8);
        if ("XMLHttpRequest".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) {
            exchange.sendResponseHeaders(204, -1); exchange.close();
        } else redirectHome(exchange);
    }

    static String buildTitleDialog(String appTitle) {
        return "<dialog id='titleDialog' class='title-dialog'><form id='titleForm'><h2>\u30a2\u30d7\u30ea\u30bf\u30a4\u30c8\u30eb\u306e\u5909\u66f4</h2><label for='appTitleInput'>\u30bf\u30a4\u30c8\u30eb</label><input id='appTitleInput' name='title' value='" + escapeHtml(appTitle) + "' maxlength='80' required><div id='titleError' class='title-error' role='alert'></div><div class='title-dialog-actions'><button type='button' id='titleCancel' class='title-dialog-cancel'>\u30ad\u30e3\u30f3\u30bb\u30eb</button><button type='submit' id='titleSave' class='title-dialog-save'>\u4fdd\u5b58</button></div></form></dialog>";
    }
    static String buildWishModal() {
        StringBuilder options = new StringBuilder();
        int otherId = 0;
        try { otherId = getOtherCategoryId(); } catch (SQLException ignored) { }
        for (Category category : categories) {
            options.append("<option value='").append(category.id).append("' data-bg='").append(escapeHtml(categoryBackgroundUrl(category.id))).append("'").append(category.id == otherId ? " selected" : "").append(">").append(escapeHtml(category.name)).append("</option>");
        }
        return "<dialog id='wishModal' class='wish-modal'><div class='wish-modal-inner'><div class='wish-modal-header'><h2 id='wishModalTitle'>\u65b0\u898f\u8ffd\u52a0</h2><button type='button' class='wish-close' id='wishClose' aria-label='\u9589\u3058\u308b'>&#215;</button></div><div id='wishError' class='form-error' role='alert'></div><form id='wishForm' class='wish-form' action='/save' method='post' enctype='multipart/form-data'><input type='hidden' name='id' id='wishId'><label for='wishTitle'>\u30bf\u30a4\u30c8\u30eb</label><input id='wishTitle' name='title' required maxlength='500'><label for='wishCategory'>\u30ab\u30c6\u30b4\u30ea\u30fc</label><select id='wishCategory' name='categoryId'>" + options + "</select><label for='wishDueDate'>\u4e88\u5b9a\u65e5</label><input id='wishDueDate' type='date' name='dueDate'><label for='wishBudget'>\u4e88\u7b97</label><input id='wishBudget' type='number' name='budget' min='0' step='1'><label>\u30b5\u30e0\u30cd\u30a4\u30eb\u753b\u50cf\uff08JPEG\u3001PNG\u3001WebP\u30015MB\u307e\u3067\uff09</label><div id='wishPreview' class='wish-preview'></div><input id='wishImage' type='file' name='image' accept='image/jpeg,image/png,image/webp'><label id='removeImageRow' class='remove-image-row' hidden><input id='removeImage' type='checkbox' name='removeImage' value='on'> \u500b\u5225\u753b\u50cf\u3092\u89e3\u9664\u3057\u3066\u30ab\u30c6\u30b4\u30ea\u30fc\u753b\u50cf\u306b\u623b\u3059</label><section id='completionFields' class='completion-fields' hidden><label for='wishCompletedDate'>\u9054\u6210\u65e5</label><input id='wishCompletedDate' type='date' name='completedDate'><label for='wishCompletedAmount'>\u5b9f\u969b\u306e\u91d1\u984d\uff08\u4efb\u610f\uff09</label><input id='wishCompletedAmount' type='number' name='completedAmount' min='0' step='1'></section><div class='wish-buttons'><button type='button' class='wish-cancel' id='wishCancel'>\u30ad\u30e3\u30f3\u30bb\u30eb</button><button type='submit' class='wish-save' id='wishSave'>\u4fdd\u5b58</button></div></form></div></dialog>";
    }
    static String categoryBackgroundUrl(int categoryId) {
        for (String extension : List.of("jpg", "png", "webp")) {
            Path image = CATEGORY_IMAGE_DIR.resolve(categoryId + "." + extension).normalize();
            if (image.startsWith(CATEGORY_IMAGE_DIR) && Files.isRegularFile(image)) return "/assets/categories/" + categoryId + "." + extension;
        }
        return "";
    }
    static void saveTodo(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        String requestType = exchange.getRequestHeaders().getFirst("Content-Type");
        Matcher boundary = requestType == null ? Pattern.compile("$").matcher("") : Pattern.compile("boundary=\"?([^;\"]+)\"?").matcher(requestType);
        if (requestType == null || !requestType.toLowerCase().startsWith("multipart/form-data") || !boundary.find()) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        byte[] body;
        try { body = readLimited(exchange.getRequestBody(), MAX_IMAGE_BYTES + 128 * 1024); }
        catch (IOException e) { sendText(exchange, 413, "\u753b\u50cf\u306f5MB\u4ee5\u4e0b\u306b\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        Map<String, byte[]> parts = parseMultipart(body, boundary.group(1));
        String idText = partText(parts, "id"), title = partText(parts, "title");
        String categoryText = partText(parts, "categoryId"), dueDate = partText(parts, "dueDate"), budgetText = partText(parts, "budget");
        boolean editing = idText != null && !idText.isBlank();
        int id = 0;
        if (editing) {
            if (!idText.matches("[0-9]{1,10}")) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
            try { id = Integer.parseInt(idText); } catch (NumberFormatException e) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
        }
        if (title == null || title.trim().isEmpty()) { sendText(exchange, 400, "\u5165\u529b\u5185\u5bb9\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"); return; }
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
            try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ?")) {
                select.setInt(1, id);
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
                ? "UPDATE todos SET title=?, category_id=?, due_date=?, budget=?, done=?, completed_date=?, completed_amount=?, image_path=? WHERE id=?"
                : "INSERT INTO todos (title, done, due_date, budget, completed_date, completed_amount, category_id, image_path) VALUES (?,0,?,?,NULL,NULL,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title.trim());
                if (editing) {
                    statement.setInt(2, categoryId); statement.setString(3, dueDate);
                    if (budget == null) statement.setNull(4, Types.INTEGER); else statement.setLong(4, budget);
                    statement.setInt(5, done ? 1 : 0);
                    if (done) statement.setString(6, completedDate); else statement.setNull(6, Types.VARCHAR);
                    if (completedAmount == null) statement.setNull(7, Types.INTEGER); else statement.setLong(7, completedAmount);
                    if (newPath == null) statement.setNull(8, Types.VARCHAR); else statement.setString(8, newPath);
                    statement.setInt(9, id);
                    if (statement.executeUpdate() != 1) throw new SQLException("item update failed");
                } else {
                    statement.setString(2, dueDate);
                    if (budget == null) statement.setNull(3, Types.INTEGER); else statement.setLong(3, budget);
                    statement.setInt(4, categoryId);
                    if (newPath == null) statement.setNull(5, Types.VARCHAR); else statement.setString(5, newPath);
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

    static void sendHtml(com.sun.net.httpserver.HttpExchange exchange, String html) throws IOException {
        byte[] response = html.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
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

    static void serveImage(com.sun.net.httpserver.HttpExchange exchange, Path base, String filename) throws IOException {
        if (!filename.matches("[a-zA-Z0-9_-]+\\.(jpg|png|webp)")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file) || Files.size(file) > MAX_IMAGE_BYTES) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
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

    static void deleteStoredImage(String relative) throws IOException {
        if (relative == null || !relative.matches("uploads/wishes/[a-f0-9-]+\\.(jpg|png|webp)")) return;
        Path file = Path.of(relative).toAbsolutePath().normalize(); if (file.startsWith(UPLOAD_DIR)) Files.deleteIfExists(file);
    }

    static void sendText(com.sun.net.httpserver.HttpExchange exchange, int status, String message) throws IOException {
        byte[] response = message.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); exchange.close();
    }

    static void redirectHome(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/"); exchange.sendResponseHeaders(303, -1); exchange.close();
    }
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

    // 実際の金額を設定します。
    void setCompletedAmount(String completedAmount) {
        this.completedAmount = completedAmount;
    }
}
