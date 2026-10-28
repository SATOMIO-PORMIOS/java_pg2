// HTTPサーバー機能を読み込みます。
import com.sun.net.httpserver.HttpServer;
// 通信の待ち受け先を指定する機能を読み込みます。
import java.net.InetSocketAddress;
// URL内の符号化された文字を元に戻す機能を読み込みます。
import java.net.URLDecoder;
// 文字コードを指定して文字列とデータを変換する機能を読み込みます。
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// HTTP処理で入出力エラーを返すための機能を読み込みます。
import java.io.IOException;
// SQLiteとの接続を扱う機能を読み込みます。
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
// 日付を扱う機能を読み込みます。
// Todoを並べて保存する機能を読み込みます。
import java.util.ArrayList;
// 文字列の一覧を扱う機能を読み込みます。
import java.util.List;

// アプリケーションの本体です。
public class App {
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final Path UPLOAD_DIR = Path.of("uploads", "wishes").toAbsolutePath().normalize();
    static final Path CATEGORY_IMAGE_DIR = Path.of("assets", "categories").toAbsolutePath().normalize();
    // データベース内のTodoを画面表示用に持ちます。
    static List<Todo> todos = new ArrayList<>();
    static List<Category> categories = new ArrayList<>();
    // SQLiteへの接続を保持します。★変更
    static Connection connection; // ★ SQLite接続

    // プログラムの開始地点です。
    public static void main(String[] args) throws Exception {
        // SQLiteデータベースへ接続します。★変更
        connection = DriverManager.getConnection("jdbc:sqlite:todos.db"); // ★ SQLite接続
        // Todoを保存する表を用意します。★変更
        try (Statement statement = connection.createStatement()) { // ★ テーブル作成
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER NOT NULL, due_date TEXT, budget INTEGER, completed_date TEXT, completed_amount INTEGER)"); // ★ テーブル作成
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)");
            statement.executeUpdate("INSERT OR IGNORE INTO categories (name) VALUES ('旅行・観光'), ('趣味・体験'), ('グルメ'), ('学習・挑戦'), ('買い物'), ('その他')");
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                boolean hasCategoryId = false;
                while (columns.next()) { if ("category_id".equals(columns.getString("name"))) hasCategoryId = true; }
                if (!hasCategoryId) statement.executeUpdate("ALTER TABLE todos ADD COLUMN category_id INTEGER REFERENCES categories(id)");
            }
            statement.executeUpdate("UPDATE todos SET category_id = (SELECT id FROM categories WHERE name = 'その他') WHERE category_id IS NULL");
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
        // SQLiteからTodo一覧を読み込みます。★変更
        loadTodos(); // ★ SELECTで読み込み
        // ポート8080（通信の入口番号）でサーバーを用意します。
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // 「/」（トップページ）へのアクセスを処理します。
        server.createContext("/", exchange -> {
            try { // ★ SQL処理
                // SQLiteから最新のTodo一覧を読み込みます。★変更
                loadTodos(); // ★ SELECTで読み込み
                // アクセスされたパス（URLの末尾部分）を取り出します。
                String path = exchange.getRequestURI().getPath();
                // HTTPメソッド（GETやPOSTなどの要求方法）を取り出します。
                String method = exchange.getRequestMethod();
                if (path.startsWith("/uploads/wishes/")) {
                    serveImage(exchange, UPLOAD_DIR, path.substring("/uploads/wishes/".length()));
                    return;
                } else if (path.startsWith("/assets/categories/")) {
                    serveImage(exchange, CATEGORY_IMAGE_DIR, path.substring("/assets/categories/".length()));
                    return;
                } else if (path.equals("/wish-image") && method.equals("POST")) {
                    handleWishImageUpload(exchange);
                    return;
                } else if (path.equals("/wish-image/remove") && method.equals("POST")) {
                    removeWishImage(exchange);
                    return;
                }
                // POSTで/addに来た内容をTodo一覧に追加します。
                if (path.equals("/add") && method.equals("POST")) {
                    // 送られてきた本文をUTF-8で読み取ります。
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    // フォームのタイトル・予定日・予算を切り出します。
                    String[] values = body.split("&");
                    // Todoのタイトルを取り出します。
                    String title = URLDecoder.decode(values[0].substring(5), StandardCharsets.UTF_8);
                    // 完了予定日を取り出します。
                    String dueDate = URLDecoder.decode(values[1].substring(8), StandardCharsets.UTF_8);
                    // 予算を取り出します。
                    String budget = URLDecoder.decode(values[2].substring(7), StandardCharsets.UTF_8);
                    String categoryValue = "";
                    for (String value : values) {
                        if (value.startsWith("categoryId=")) categoryValue = URLDecoder.decode(value.substring(11), StandardCharsets.UTF_8);
                    }
                    int categoryId = categoryValue.isEmpty() ? getOtherCategoryId() : Integer.parseInt(categoryValue);
                    // Todoが空でない場合だけSQLiteへ追加します。★変更
                    if (!title.isEmpty()) { // ★ INSERT
                        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO todos (title, done, due_date, budget, completed_date, completed_amount, category_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) { // ★ INSERT
                            statement.setString(1, title); // ★ INSERT
                            statement.setInt(2, 0); // ★ INSERT
                            statement.setString(3, dueDate); // ★ INSERT
                            if (budget.isEmpty()) { statement.setNull(4, Types.INTEGER); } else { statement.setLong(4, Long.parseLong(budget)); } // ★ INSERT
                            statement.setString(5, ""); // ★ INSERT
                            statement.setNull(6, Types.INTEGER); // ★ INSERT
                            statement.setInt(7, categoryId);
                            statement.executeUpdate(); // ★ INSERT
                        }
                    }
                    // 戻り先をトップページにします。
                    exchange.getResponseHeaders().set("Location", "/");
                    // 303でトップページへの移動を伝えます。
                    exchange.sendResponseHeaders(303, -1);
                    // 応答を閉じます。
                    exchange.close();
                    // この分岐の処理を終えます。
                    return;
                // GET /doneで完了状態と金額を登録します。
                } else if (path.equals("/done") && method.equals("GET")) {
                    // URLのクエリ（?の後ろの文字列）を受け取ります。
                    String query = exchange.getRequestURI().getQuery();
                    // idが付いているか確かめます。
                    if (query != null && query.startsWith("id=") && query.length() > 3) {
                        // idと金額を切り分けます。
                        String[] queryValues = query.split("&");
                        // 数字のidを取り出します。
                        try {
                            int id = Integer.parseInt(queryValues[0].substring(3));
                            // 金額を取り出します。
                            String selectedDate = queryValues.length > 1 ? URLDecoder.decode(queryValues[1].substring(5), StandardCharsets.UTF_8) : ""; // ★ カレンダーの完了日を取得
                            String completedAmount = queryValues.length > 2 ? URLDecoder.decode(queryValues[2].substring(7), StandardCharsets.UTF_8) : ""; // ★ 完了時の金額を取得
                            // Todoを1件ずつ調べます。
                            for (Todo todo : todos) {
                                // idが一致したTodoをSQLiteで更新します。★変更
                                if (todo.getId() == id) { // ★ UPDATE
                                    boolean newDone = !selectedDate.isEmpty(); // ★ 日付の有無を完了状態に反映
                                    String completedDate = newDone ? selectedDate : ""; // ★ 選択日を保存
                                    try (PreparedStatement statement = connection.prepareStatement("UPDATE todos SET done = ?, completed_date = ?, completed_amount = ? WHERE id = ?")) { // ★ UPDATE
                                        statement.setInt(1, newDone ? 1 : 0); // ★ UPDATE
                                        statement.setString(2, completedDate); // ★ UPDATE
                                        if (newDone && !completedAmount.isEmpty()) { statement.setLong(3, Long.parseLong(completedAmount)); } else { statement.setNull(3, Types.INTEGER); } // ★ 金額を保存
                                        statement.setInt(4, id); // ★ UPDATE
                                        statement.executeUpdate(); // ★ UPDATE
                                    }
                                    // 一致したTodoの確認を終えます。
                                    break;
                                }
                            }
                        // 数字でないidは何もせず受け止めます。
                        } catch (NumberFormatException e) {
                        }
                    }
                    // 戻り先をトップページにします。
                    exchange.getResponseHeaders().set("Location", "/");
                    // 303でトップページへの移動を伝えます。
                    exchange.sendResponseHeaders(303, -1);
                    // 応答を閉じます。
                    exchange.close();
                    // この分岐の処理を終えます。
                    return;
                // GET /deleteでTodoを削除します。
                } else if (path.equals("/delete") && method.equals("GET")) {
                    // URLのクエリ（?の後ろの文字列）を受け取ります。
                    String query = exchange.getRequestURI().getQuery();
                    // idが付いているか確かめます。
                    if (query != null && query.startsWith("id=") && query.length() > 3) {
                        // 数字のidを取り出します。
                        try {
                            int id = Integer.parseInt(query.substring(3));
                            String oldImagePath = null;
                            try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ?")) {
                                select.setInt(1, id);
                                try (ResultSet row = select.executeQuery()) { if (row.next()) oldImagePath = row.getString(1); }
                            }
                            // idが一致するTodoをSQLiteから削除します。★変更
                            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM todos WHERE id = ?")) { // ★ DELETE
                                statement.setInt(1, id); // ★ DELETE
                                statement.executeUpdate(); // ★ DELETE
                            }
                            deleteStoredImage(oldImagePath);
                        // 数字でないidは何もせず受け止めます。
                        } catch (NumberFormatException e) {
                        }
                    }
                    // 戻り先をトップページにします。
                    exchange.getResponseHeaders().set("Location", "/");
                    // 303でトップページへの移動を伝えます。
                    exchange.sendResponseHeaders(303, -1);
                    // 応答を閉じます。
                    exchange.close();
                    // この分岐の処理を終えます。
                    return;
                // トップページにフォームとTodo一覧を表示します。
                } else if (path.equals("/")) {
                    // 見出し・フォーム・一覧を含むページを組み立てます。
                    String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>やりたいことリスト</title><link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Yusei+Magic&display=swap' rel='stylesheet'><style>* { box-sizing: border-box; } body { max-width: 1100px; margin: 20px auto; padding: 0 16px; font-size: 16px; font-family: 'Yusei Magic', 'Courier New', monospace; color: #30283a; } .filter-row { margin: 12px 0; } .category-label { display: inline-block; padding: 2px 8px; border-radius: 999px; background: #eee4fa; color: #55416f; font-size: 10px; font-weight: 700; } .image-form { display: inline-flex; align-items: center; gap: 4px; margin-top: 2px; font-size: 10px; } .image-form input { max-width: 150px; font-size: 10px; } .image-remove { border: 0; padding: 2px 6px; border-radius: 8px; background: #f8dce4; color: #63384a; font-size: 10px; cursor: pointer; } .progress-summary { margin: 18px 0; padding: 14px 16px; border-radius: 14px; background: #f5f0fa; box-shadow: 0 3px 12px #30283a18; } .progress-label { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 8px; font-weight: 700; } .progress-track { height: 12px; overflow: hidden; border-radius: 999px; background: #ded7e7; } .progress-fill { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #ff8ca8, #a678e2); transition: width 250ms ease; } ul.todo-list { list-style: none; padding: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; } li.note { position: relative; height: 260px; min-width: 0; overflow: hidden; border-radius: 16px; padding: 12px; display: flex; flex-direction: column; justify-content: flex-end; box-shadow: 0 5px 16px #30283a2b; background-color: #f4c7d0; background-image: linear-gradient(145deg, #ffca9a, #a9d9e8 48%, #d4b8e7); background-size: cover; background-position: center; } .note-panel { position: relative; min-width: 0; width: 100%; max-height: 100%; padding: 10px 11px 8px; border-radius: 11px; background: rgba(255,255,255,.82); display: flex; flex-direction: column; gap: 6px; } .note-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; min-width: 0; } .note-title { min-width: 0; margin: 0; font-size: 16px; line-height: 1.3; font-weight: 700; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; overflow-wrap: anywhere; } .done-mark { flex: 0 0 auto; color: #c63865; font-size: 14px; font-weight: 700; } .note-details { min-width: 0; font-size: 12px; line-height: 1.45; overflow-wrap: anywhere; } .note-actions { display: flex; align-items: center; justify-content: flex-end; gap: 6px; margin-top: 0; min-height: 30px; flex-wrap: wrap; } .calendar-button { position: relative; display: inline-flex; flex: 0 0 30px; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 50%; background: #ffffffa8; cursor: pointer; } .icon-image { width: 16px; height: 16px; object-fit: contain; vertical-align: middle; } .complete-date { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; } .delete-icon { display: inline-flex; flex: 0 0 30px; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 50%; background: #ffffffa8; text-decoration: none; transition: transform 150ms ease, background 150ms ease; } .delete-icon:hover { background: #ffc8d5; transform: scale(1.08); } .note-id { font-size: 10px; color: #665d70; } @media (max-width: 760px) { ul.todo-list { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 520px) { ul.todo-list { grid-template-columns: 1fr; } }</style></head><body><h1>やりたいことリスト</h1><form method='post' action='/add'><input name='todo'><input type='date' name='dueDate'><input type='number' name='budget' min='0' step='1' placeholder='予算'><select name='categoryId'>";
                    for (Category category : categories) html += "<option value='" + category.id + "'>" + category.name + "</option>";
                    html += "</select><button>追加</button></form>";
                    // Todoがない場合は案内を表示します。
                    // Todoがない場合は案内を表示します。
                    if (todos.isEmpty()) { html += "<p>やることを探しましょう</p>"; }
                    // 全Todoの完了率と未完了予算を計算します。
                    int completedCount = 0;
                    long pendingBudgetTotal = 0;
                    for (Todo todo : todos) {
                        if (todo.isDone()) { completedCount++; }
                        else if (!todo.getBudget().isEmpty()) { pendingBudgetTotal += Long.parseLong(todo.getBudget()); }
                    }
                    int completionPercent = todos.isEmpty() ? 0 : completedCount * 100 / todos.size();
                    String selectedCategory = exchange.getRequestURI().getQuery();
                    String selectedCategoryId = selectedCategory != null && selectedCategory.startsWith("categoryId=") ? selectedCategory.substring(11) : "";
                    html += "<div class='list-content'><section class='progress-summary' aria-label='達成率'><div class='progress-label'><span>達成率</span><span>" + completionPercent + "%</span></div><div class='progress-track' role='progressbar' aria-valuenow='" + completionPercent + "' aria-valuemin='0' aria-valuemax='100' aria-label='達成率'><div class='progress-fill' style='width: " + completionPercent + "%'></div></div><p>未完了の予算合計: " + String.format("%,d", pendingBudgetTotal) + "円</p></section><form class='filter-row' method='get' action='/'><label>カテゴリーで絞り込み <select name='categoryId' onchange='this.form.submit()'><option value=''>すべて</option>";
                    for (Category category : categories) html += "<option value='" + category.id + "' " + (String.valueOf(category.id).equals(selectedCategoryId) ? "selected" : "") + ">" + category.name + "</option>";
                    html += "</select></label></form><ul class='todo-list'>";
                    // Todoを1件ずつページへ追加します。
                    int number = 1;
                    for (Todo todo : todos) {
                        if (!selectedCategoryId.isEmpty() && !String.valueOf(todo.getCategoryId()).equals(selectedCategoryId)) continue;
                        // 未完了時はチェック印の幅の空き、完了時はチェック印を表示します。
                        // 完了済みTodoには完了日と金額を表示します。
                        String completedText = "";
                        if (todo.isDone()) {
                            String amountText = todo.getCompletedAmount().isEmpty() ? "" : "、" + String.format("%,d", Long.parseLong(todo.getCompletedAmount())) + "円";
                            completedText = "<br>達成日: " + todo.getCompletedDate() + (todo.getCompletedAmount().isEmpty() ? "" : "<br>実際の金額: " + String.format("%,d", Long.parseLong(todo.getCompletedAmount())) + "円");
                        }
                        // Todoの番号・題名・予定日・予算・完了情報・操作リンクを一覧へ足します。
                        String budgetText = todo.getBudget().isEmpty() ? "未設定" : String.format("%,d", Long.parseLong(todo.getBudget())) + "円";
                        String backgroundUrl = getBackgroundUrl(todo);
                        html += "<li class='note " + (todo.isDone() ? "done" : "pending") + "' style=\"background-image: linear-gradient(0deg, rgba(25,20,35,.18), rgba(25,20,35,.04)), url('" + backgroundUrl + "')\"><div class='note-panel'><div class='note-heading'><h2 class='note-title'>" + number + ". " + todo.getTitle() + "</h2>" + (todo.isDone() ? "<span class='done-mark'>✓ 達成</span>" : "") + "</div><span class='category-label'>" + todo.getCategoryName() + "</span><div class='note-details'>予定日: " + todo.getDueDate() + "<br>予算: " + budgetText + completedText + "</div><div class='note-actions'><label class='calendar-button' title='完了日をカレンダーから選択'><img src='/assets/calendar.png' alt='' class='icon-image'><input aria-label='完了日をカレンダーから選択' class='complete-date' type='date' data-id='" + todo.getId() + "' data-original='" + todo.getCompletedDate() + "' value='" + todo.getCompletedDate() + "'></label><a href='/delete?id=" + todo.getId() + "' title='削除' aria-label='削除' class='delete-icon' onclick=\"return confirm('この項目を削除してもよろしいですか？');\"><img src='/assets/trash.png' alt='' class='icon-image'></a><form class='image-form' method='post' action='/wish-image' enctype='multipart/form-data'><input type='hidden' name='id' value='" + todo.getId() + "'><input type='file' name='image' accept='image/jpeg,image/png,image/webp' aria-label='背景画像を選択'><button type='submit'>画像変更</button></form><form method='post' action='/wish-image/remove'><input type='hidden' name='id' value='" + todo.getId() + "'><button class='image-remove' type='submit'>画像解除</button></form><span class='note-id'>ID " + todo.getId() + "</span></div></div></li>";
                        number++;
                    }
                    // Todo一覧を閉じます。
                    html += "</ul></div>";
                    // ページをUTF-8のデータに変換します。
                    byte[] responseBody = html.getBytes("UTF-8");
                    // 正常応答（200）とデータの長さを送ります。
                    exchange.sendResponseHeaders(200, responseBody.length);
                    // ページのデータをブラウザへ送ります。
                    exchange.getResponseBody().write(responseBody);
                    // ブラウザへの応答を閉じます。
                    exchange.getResponseBody().close();
                // どの分岐にも当てはまらない場合の応答です。
                } else {
                    // 見つからないページの文字を用意します。
                    String message = "ページが見つかりません";
                    // 返す文字がUTF-8だと伝えます。
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    // 返す文字をUTF-8のデータに変換します。
                    byte[] responseBody = message.getBytes("UTF-8");
                    // 正常応答（200）とデータの長さを送ります。
                    exchange.sendResponseHeaders(200, responseBody.length);
                    // 文字のデータをブラウザへ送ります。
                    exchange.getResponseBody().write(responseBody);
                    // ブラウザへの応答を閉じます。
                    exchange.getResponseBody().close();
                }
            } catch (SQLException e) { // ★ SQLエラーをHTTP入出力エラーとして渡します。
                throw new IOException(e); // ★ SQLエラー処理
            }
        });
        // カレンダーアイコン画像を配信します。
        server.createContext("/assets/calendar.png", exchange -> {
            byte[] icon = Files.readAllBytes(Path.of("assets/calendar.png"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, icon.length);
            exchange.getResponseBody().write(icon);
            exchange.close();
        });
        // ゴミ箱アイコン画像を配信します。
        server.createContext("/assets/trash.png", exchange -> {
            byte[] icon = Files.readAllBytes(Path.of("assets/trash.png"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, icon.length);
            exchange.getResponseBody().write(icon);
            exchange.close();
        });
        // サーバーの待ち受けを始めます。
        server.start();
        // 起動したことと停止方法を表示します。
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）");
    }

    // SQLiteの表からTodoを読み込みます。★変更
    static void loadTodos() throws SQLException { // ★ SELECT
        todos.clear(); // ★ SELECT
        try (PreparedStatement statement = connection.prepareStatement("SELECT t.id, t.title, t.done, t.due_date, t.budget, t.completed_date, t.completed_amount, t.category_id, t.image_path, c.name AS category_name FROM todos t LEFT JOIN categories c ON c.id = t.category_id ORDER BY t.done ASC, t.id ASC")) { // ★ SELECT
            try (ResultSet result = statement.executeQuery()) { // ★ SELECT
                while (result.next()) { // ★ SELECT
                    String budget = result.getString("budget"); // ★ SELECT
                    String completedAmount = result.getString("completed_amount"); // ★ SELECT
                    Todo todo = new Todo(result.getInt("id"), result.getString("title"), result.getString("due_date"), budget == null ? "" : budget); // ★ SELECT
                    todo.setDone(result.getInt("done") != 0); // ★ SELECT
                    todo.setCompletedDate(result.getString("completed_date")); // ★ SELECT
                    todo.setCompletedAmount(completedAmount == null ? "" : completedAmount); // ★ SELECT
                    todo.setCategory(result.getInt("category_id"), result.getString("category_name") == null ? "その他" : result.getString("category_name"));
                    todo.setImagePath(result.getString("image_path"));
                    todo.setImagePath(result.getString("image_path"));
                    todos.add(todo); // ★ SELECT
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM categories WHERE name = 'その他'")) {
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getInt("id") : categories.get(categories.size() - 1).id; }
        }
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

    static void handleWishImageUpload(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        Matcher boundaryMatcher = contentType == null ? Pattern.compile("$").matcher("") : Pattern.compile("boundary=\"?([^;\"]+)\"?").matcher(contentType);
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data") || !boundaryMatcher.find()) { sendText(exchange, 400, "画像フォームを読み取れません"); return; }
        byte[] body;
        try { body = readLimited(exchange.getRequestBody(), MAX_IMAGE_BYTES + 64 * 1024); }
        catch (IOException e) { sendText(exchange, 413, "画像は5MB以下にしてください"); return; }
        String boundary = boundaryMatcher.group(1);
        Map<String, byte[]> parts = parseMultipart(body, boundary);
        String idText = parts.containsKey("id") ? new String(parts.get("id"), StandardCharsets.UTF_8) : "";
        String imagePart = null;
        String headerText = new String(body, 0, Math.min(body.length, 2000), StandardCharsets.ISO_8859_1);
        Matcher imageHeader = Pattern.compile("name=\"image\"[^\\r\\n]*\\r\\n(?:Content-Type:\s*([^\\r\\n]+)\\r\\n)?\\r\\n", Pattern.CASE_INSENSITIVE).matcher(headerText);
        if (imageHeader.find()) imagePart = imageHeader.group(1);
        byte[] image = parts.get("image");
        if (!idText.matches("[0-9]{1,10}") || image == null || image.length == 0) { sendText(exchange, 400, "画像または項目IDがありません"); return; }
        if (image.length > MAX_IMAGE_BYTES) { sendText(exchange, 413, "画像は5MB以下にしてください"); return; }
        String extension = detectImageExtension(image);
        String declared = imagePart == null ? "" : imagePart.trim().toLowerCase();
        if (extension == null || !(declared.isEmpty() || (extension.equals("jpg") && declared.equals("image/jpeg")) || declared.equals("image/" + extension))) { sendText(exchange, 415, "JPEG、PNG、WebP画像のみ使用できます"); return; }
        int id = Integer.parseInt(idText);
        String filename = UUID.randomUUID().toString() + "." + extension;
        Path destination = UPLOAD_DIR.resolve(filename).normalize();
        if (!destination.startsWith(UPLOAD_DIR)) { sendText(exchange, 400, "保存先が不正です"); return; }
        Path temp = Files.createTempFile(UPLOAD_DIR, "upload-", ".tmp");
        String oldPath = null;
        boolean saved = false;
        try {
            Files.write(temp, image);
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ?")) {
                select.setInt(1, id);
                try (ResultSet row = select.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("項目が見つかりません"); oldPath = row.getString(1); }
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            saved = true;
            try (PreparedStatement update = connection.prepareStatement("UPDATE todos SET image_path = ? WHERE id = ?")) {
                update.setString(1, "uploads/wishes/" + filename); update.setInt(2, id);
                if (update.executeUpdate() != 1) throw new SQLException("項目を更新できません");
            }
            connection.commit();
        } catch (IllegalArgumentException e) {
            connection.rollback(); if (saved) Files.deleteIfExists(destination); sendText(exchange, 404, e.getMessage()); return;
        } catch (Exception e) {
            connection.rollback(); if (saved) Files.deleteIfExists(destination); sendText(exchange, 500, "画像を保存できませんでした"); return;
        } finally {
            connection.setAutoCommit(true); Files.deleteIfExists(temp);
        }
        deleteStoredImage(oldPath);
        redirectHome(exchange);
    }

    static void removeWishImage(com.sun.net.httpserver.HttpExchange exchange) throws IOException, SQLException {
        String body = new String(readLimited(exchange.getRequestBody(), 4096), StandardCharsets.UTF_8);
        String idText = formValue(body, "id");
        if (idText == null || !idText.matches("[0-9]{1,10}")) { sendText(exchange, 400, "項目IDが不正です"); return; }
        String oldPath = null;
        int id = Integer.parseInt(idText);
        try (PreparedStatement select = connection.prepareStatement("SELECT image_path FROM todos WHERE id = ?")) {
            select.setInt(1, id);
            try (ResultSet row = select.executeQuery()) { if (row.next()) oldPath = row.getString(1); }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE todos SET image_path = NULL WHERE id = ?")) { update.setInt(1, id); update.executeUpdate(); }
        deleteStoredImage(oldPath);
        redirectHome(exchange);
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

// Todo1件の番号・内容・完了状態・予定日・完了日・金額を持つクラスです。
class Todo {
    // Todoの番号です。
    private final int id;
    // Todoの内容です。
    private final String title;
    // Todoが完了したかどうかです。
    private boolean done;
    // Todoの完了予定日です。
    private final String dueDate;
    // Todoに設定した予算です。
    private final String budget;
    // Todoの完了日です。
    private String completedDate = "";
    // Todoの完了金額です。
    private String completedAmount = "";
    private int categoryId;
    private String imagePath;
    private String categoryName = "その他";

    // Todoの番号・内容・予定日・予算を受け取って作ります。
    Todo(int id, String title, String dueDate, String budget) {
        this.id = id;
        this.title = title;
        this.dueDate = dueDate;
        this.budget = budget;
        this.done = false;
    }

    // Todoの番号を読み出します。
    int getId() {
        return id;
    }

    // Todoの内容を読み出します。
    String getTitle() {
        return title;
    }

    // Todoが完了済みか読み出します。
    boolean isDone() {
        return done;
    }

    // Todoの完了状態を書き換えます。
    void setDone(boolean done) {
        this.done = done;
    }

    // Todoの予定日を読み出します。
    String getDueDate() {
        return dueDate;
    }

    // Todoの予算を読み出します。
    String getBudget() {
        return budget;
    }

    // Todoの完了日を読み出します。
    String getCompletedDate() {
        return completedDate;
    }

    // Todoの完了日を書き換えます。
    void setCompletedDate(String completedDate) {
        this.completedDate = completedDate;
    }

    // Todoの完了金額を読み出します。
    String getCompletedAmount() {
        return completedAmount;
    }

    int getCategoryId() { return categoryId; }
    String getImagePath() { return imagePath; }
    void setImagePath(String imagePath) { this.imagePath = imagePath; }
    String getCategoryName() { return categoryName; }
    void setCategory(int categoryId, String categoryName) { this.categoryId = categoryId; this.categoryName = categoryName; }

    // Todoの完了金額を書き換えます。
    void setCompletedAmount(String completedAmount) {
        this.completedAmount = completedAmount;
    }
}
