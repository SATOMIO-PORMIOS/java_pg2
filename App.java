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
    // データベース内のTodoを画面表示用に持ちます。
    static List<Todo> todos = new ArrayList<>();
    // SQLiteへの接続を保持します。★変更
    static Connection connection; // ★ SQLite接続

    // プログラムの開始地点です。
    public static void main(String[] args) throws Exception {
        // SQLiteデータベースへ接続します。★変更
        connection = DriverManager.getConnection("jdbc:sqlite:todos.db"); // ★ SQLite接続
        // Todoを保存する表を用意します。★変更
        try (Statement statement = connection.createStatement()) { // ★ テーブル作成
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER NOT NULL, due_date TEXT, budget INTEGER, completed_date TEXT, completed_amount INTEGER)"); // ★ テーブル作成
        }
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
                    // Todoが空でない場合だけSQLiteへ追加します。★変更
                    if (!title.isEmpty()) { // ★ INSERT
                        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO todos (title, done, due_date, budget, completed_date, completed_amount) VALUES (?, ?, ?, ?, ?, ?)")) { // ★ INSERT
                            statement.setString(1, title); // ★ INSERT
                            statement.setInt(2, 0); // ★ INSERT
                            statement.setString(3, dueDate); // ★ INSERT
                            if (budget.isEmpty()) { statement.setNull(4, Types.INTEGER); } else { statement.setLong(4, Long.parseLong(budget)); } // ★ INSERT
                            statement.setString(5, ""); // ★ INSERT
                            statement.setNull(6, Types.INTEGER); // ★ INSERT
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
                            // idが一致するTodoをSQLiteから削除します。★変更
                            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM todos WHERE id = ?")) { // ★ DELETE
                                statement.setInt(1, id); // ★ DELETE
                                statement.executeUpdate(); // ★ DELETE
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
                // トップページにフォームとTodo一覧を表示します。
                } else if (path.equals("/")) {
                    // 見出し・フォーム・一覧を含むページを組み立てます。
                    String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>やりたいことリスト</title><link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Yusei+Magic&display=swap' rel='stylesheet'><style>body { max-width: 1000px; margin: 20px auto; padding: 0 16px; font-size: 16px; font-family: 'Yusei Magic', 'Courier New', monospace; }ul.todo-list { list-style: none; padding-left: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; } li.note { background: #fff3a6; padding: 18px; min-height: 110px; display: flex; flex-direction: column; box-shadow: 2px 4px 8px #0002; transform: rotate(-1deg); } li.note.done { background: #f7c6d0; } .calendar-button { position: relative; display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 50%; background: transparent; font-size: 19px; cursor: pointer; } .icon-image { width: 16px; height: 16px; object-fit: contain; vertical-align: middle; } .complete-date { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; } .delete-icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 50%; background: transparent; text-decoration: none; font-size: 17px; transition: transform 150ms ease, background 150ms ease; } .delete-icon:hover { background: #ffc8d5; transform: scale(1.08); } .note-details { font-size: 13px; } .note-actions { font-size: 13px; } .note-id { font-size: 11px; } .note-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: auto; }</style></head><body><h1>やりたいことリスト</h1><form method='post' action='/add'><input name='todo'><input type='date' name='dueDate'><input type='number' name='budget' min='0' step='1' placeholder='予算'><button>追加</button></form>";
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
                    html += "<p>完了率: " + completionPercent + "%　未完了の予算合計: " + String.format("%,d", pendingBudgetTotal) + "円</p><ul class='todo-list'>";
                    // Todoを1件ずつページへ追加します。
                    int number = 1;
                    for (Todo todo : todos) {
                        // 未完了時はチェック印の幅の空き、完了時はチェック印を表示します。
                        // 完了済みTodoには完了日と金額を表示します。
                        String completedText = "";
                        if (todo.isDone()) {
                            String amountText = todo.getCompletedAmount().isEmpty() ? "" : "、" + String.format("%,d", Long.parseLong(todo.getCompletedAmount())) + "円";
                            completedText = "（完了日: " + todo.getCompletedDate() + amountText + "）";
                        }
                        // Todoの番号・題名・予定日・予算・完了情報・操作リンクを一覧へ足します。
                        String budgetText = todo.getBudget().isEmpty() ? "未設定" : String.format("%,d", Long.parseLong(todo.getBudget())) + "円";
                        html += "<li class='note " + (todo.isDone() ? "done" : "pending") + "'><div><span>" + number + ". " + todo.getTitle() + "</span><div class='note-details'>（予定日: " + todo.getDueDate() + "、予算: " + budgetText + "）" + completedText + "</div></div><div class='note-actions'><label class='calendar-button' title='完了日をカレンダーから選択'><img src='/assets/calendar.png' alt='' class='icon-image'><input aria-label='完了日をカレンダーから選択' class='complete-date' type='date' data-id='" + todo.getId() + "' data-original='" + todo.getCompletedDate() + "' value='" + todo.getCompletedDate() + "'></label> <a href='/delete?id=" + todo.getId() + "' title='削除' aria-label='削除' class='delete-icon' onclick=\"return confirm('この項目を削除してもよろしいですか？');\"><img src='/assets/trash.png' alt='' class='icon-image'></a><span class='note-id'>ID " + todo.getId() + "</span></div></li>";
                        number++;
                    }
                    // Todo一覧を閉じます。
                    html += "</ul>";
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, title, done, due_date, budget, completed_date, completed_amount FROM todos ORDER BY done ASC, id ASC")) { // ★ SELECT
            try (ResultSet result = statement.executeQuery()) { // ★ SELECT
                while (result.next()) { // ★ SELECT
                    String budget = result.getString("budget"); // ★ SELECT
                    String completedAmount = result.getString("completed_amount"); // ★ SELECT
                    Todo todo = new Todo(result.getInt("id"), result.getString("title"), result.getString("due_date"), budget == null ? "" : budget); // ★ SELECT
                    todo.setDone(result.getInt("done") != 0); // ★ SELECT
                    todo.setCompletedDate(result.getString("completed_date")); // ★ SELECT
                    todo.setCompletedAmount(completedAmount == null ? "" : completedAmount); // ★ SELECT
                    todos.add(todo); // ★ SELECT
                }
            }
        }
    }
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

    // Todoの完了金額を書き換えます。
    void setCompletedAmount(String completedAmount) {
        this.completedAmount = completedAmount;
    }
}
