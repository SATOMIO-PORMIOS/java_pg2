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
// 日付を扱う機能を読み込みます。
import java.time.LocalDate;
// Todoを並べて保存する機能を読み込みます。
import java.util.ArrayList;
// 文字列の一覧を扱う機能を読み込みます。
import java.util.List;

// アプリケーションの本体です。
public class App {
    // Todoの一覧をクラス全体で共有します。
    static List<Todo> todos = new ArrayList<>();
    // 次にTodoへ振る番号を保存します。
    static int nextId = 1;

    // プログラムの開始地点です。
    public static void main(String[] args) throws Exception {
        // ポート8080（通信の入口番号）でサーバーを用意します。
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // todos.csvからTodoを読み込みます。
        load();
        // 「/」（トップページ）へのアクセスを処理します。
        server.createContext("/", exchange -> {
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
                // Todoが空でない場合だけ新しいTodoを追加します。
                if (!title.isEmpty()) {
                    // 次の番号のTodoを一覧に追加します。
                    todos.add(new Todo(nextId, title, dueDate, budget));
                    // 次に使う番号を1つ進めます。
                    nextId++;
                    // 変更した一覧をtodos.csvへ保存します。
                    save();
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
                        String completedAmount = queryValues.length > 1 ? URLDecoder.decode(queryValues[1].substring(7), StandardCharsets.UTF_8) : "";
                        // Todoを1件ずつ調べます。
                        for (Todo todo : todos) {
                            // idが一致したTodoの完了状態を切り替えます。
                            if (todo.getId() == id) {
                                // 完了にする場合は今日の日付と金額を記録します。
                                if (!todo.isDone()) {
                                    todo.setCompletedDate(LocalDate.now().toString());
                                    todo.setCompletedAmount(completedAmount);
                                } else {
                                    todo.setCompletedDate("");
                                    todo.setCompletedAmount("");
                                }
                                todo.setDone(!todo.isDone());
                                // 完了状態の変更をtodos.csvへ保存します。
                                save();
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
                        // idが一致するTodoを一覧から削除します。
                        todos.removeIf(todo -> todo.getId() == id);
                        // 削除後の一覧をtodos.csvへ保存します。
                        save();
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
                String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>やりたいことリスト</title><link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Kaisei+Tokumin&display=swap' rel='stylesheet'><style>body { max-width: 600px; margin: 20px auto; padding: 0 16px; font-size: 16px; font-family: 'Kaisei Tokumin', 'Courier New', monospace; }ul { list-style: none; padding-left: 0; }</style></head><body><h1>やりたいことリスト</h1><form method='post' action='/add'><input name='todo'><input type='date' name='dueDate'><input type='number' name='budget' min='0' step='1' placeholder='予算'><button>追加</button></form>";
                // Todoがない場合は案内を表示します。
                if (todos.isEmpty()) {
                    html += "<p>やることを探しましょう</p>";
                }
                // 表示用の通し番号を1から始めます。
                int number = 1;
                // 完了済みTodoの数を数えます。
                int completedCount = 0;
                for (Todo todo : todos) {
                    if (todo.isDone()) {
                        completedCount++;
                    }
                }
                // Todo全体に対する完了率を計算して表示します。
                int completionPercent = todos.isEmpty() ? 0 : completedCount * 100 / todos.size();
                html += "<p>完了率: " + completionPercent + "%</p><ul>";
                // Todoを1件ずつページへ追加します。
                for (Todo todo : todos) {
                    // 未完了時はチェック印の幅の空き、完了時はチェック印を表示します。
                    String mark = "&nbsp;";
                    if (todo.isDone()) {
                        mark = "✓";
                    }
                    // 完了済みTodoには完了日と金額を表示します。
                    String completedText = "";
                    if (todo.isDone()) {
                        String amountText = todo.getCompletedAmount().isEmpty() ? "" : "、" + String.format("%,d", Long.parseLong(todo.getCompletedAmount())) + "円";
                        completedText = "（完了日: " + todo.getCompletedDate() + amountText + "）";
                    }
                    // Todoの番号・題名・予定日・予算・完了情報・操作リンクを一覧へ足します。
                    String budgetText = todo.getBudget().isEmpty() ? "未設定" : String.format("%,d", Long.parseLong(todo.getBudget())) + "円";
                    html += "<li>" + number + ". <a href='/done?id=" + todo.getId() + "&amp;amount='>" + mark + "</a> " + todo.getId() + ". " + todo.getTitle() + "（予定日: " + todo.getDueDate() + "、予算: " + budgetText + "）" + completedText + " <a href='/delete?id=" + todo.getId() + "'>削除</a></li>";
                    number++;
                }
                // Todo一覧とページを閉じます。
                html += "</ul><script>document.querySelectorAll('a[href^=\"/done\"]').forEach(link => { link.addEventListener('click', event => { event.preventDefault(); const amount = prompt('完了金額を入力してください', ''); if (amount !== null) { location.href = link.href + encodeURIComponent(amount); } }); });</script></body></html>";
                // HTMLとUTF-8をブラウザに伝えます。
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
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
        });
        // サーバーの待ち受けを始めます。
        server.start();
        // 起動したことと停止方法を表示します。
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）");
    }
    // Todo一覧をUTF-8のCSVファイルへ保存します。
    static void save() throws java.io.IOException {
        List<String> rows = new ArrayList<>();
        for (Todo todo : todos) {
            rows.add(todo.getId() + ",\"" + todo.getTitle().replace("\"", "\"\"") + "\"," + todo.isDone() + ",\"" + todo.getDueDate().replace("\"", "\"\"") + "\",\"" + todo.getBudget().replace("\"", "\"\"") + "\",\"" + todo.getCompletedDate().replace("\"", "\"\"") + "\",\"" + todo.getCompletedAmount().replace("\"", "\"\"") + "\"");
        }
        Files.write(Path.of("todos.csv"), rows, StandardCharsets.UTF_8);
    }

    // UTF-8のCSVファイルからTodo一覧を読み込み、次の番号を決めます。
    static void load() throws java.io.IOException {
        Path file = Path.of("todos.csv");
        if (Files.exists(file)) {
            for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] fields = row.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                String title = fields[1].replaceAll("^\"|\"$", "").replace("\"\"", "\"");
                String dueDate = fields[3].replaceAll("^\"|\"$", "").replace("\"\"", "\"");
                String budget = fields[4].replaceAll("^\"|\"$", "").replace("\"\"", "\"");
                String completedDate = fields[5].replaceAll("^\"|\"$", "").replace("\"\"", "\"");
                String completedAmount = fields[6].replaceAll("^\"|\"$", "").replace("\"\"", "\"");
                Todo todo = new Todo(Integer.parseInt(fields[0]), title, dueDate, budget);
                todo.setDone(Boolean.parseBoolean(fields[2]));
                todo.setCompletedDate(completedDate);
                todo.setCompletedAmount(completedAmount);
                todos.add(todo);
                nextId = Math.max(nextId, todo.getId() + 1);
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
