// HTTPサーバー機能を読み込みます。
import com.sun.net.httpserver.HttpServer;
// 通信の待ち受け先を指定する機能を読み込みます。
import java.net.InetSocketAddress;
// URL内の符号化された文字を元に戻す機能を読み込みます。
import java.net.URLDecoder;
// 文字コードを指定して文字列とデータを変換する機能を読み込みます。
import java.nio.charset.StandardCharsets;
// Todoを並べて保存する機能を読み込みます。
import java.util.ArrayList;
// 文字列の一覧を扱う機能を読み込みます。
import java.util.List;

// アプリケーションの本体です。
public class App {
    // Todoの一覧をクラス全体で共有します。★変更
    static List<Todo> todos = new ArrayList<>();
    // 次にTodoへ振る番号を保存します。★変更
    static int nextId = 1;

    // プログラムの開始地点です。
    public static void main(String[] args) throws Exception {
        // ポート8080（通信の入口番号）でサーバーを用意します。
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // 起動時のサンプルTodoを追加します。★変更
        todos.add(new Todo(nextId++, "牛乳を買う"));
        // 2件目のサンプルTodoを作ります。★変更
        Todo egg = new Todo(nextId++, "卵を買う");
        // 2件目のサンプルを完了済みにします。★変更
        egg.setDone(true);
        // 2件目のサンプルTodoを一覧に加えます。★変更
        todos.add(egg);
        // 「/」（トップページ）へのアクセスを処理します。
        server.createContext("/", exchange -> {
            // アクセスされたパス（URLの末尾部分）を取り出します。
            String path = exchange.getRequestURI().getPath();
            // HTTPメソッド（GETやPOSTなどの要求方法）を取り出します。
            String method = exchange.getRequestMethod();
            // POSTで/addに来た内容をTodo一覧に追加します。★変更
            if (path.equals("/add") && method.equals("POST")) {
                // 送られてきた本文をUTF-8で読み取ります。
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // todo=の後ろを切り出します。
                String value = body.substring(5);
                // URLデコードして日本語を元に戻します。
                String title = URLDecoder.decode(value, StandardCharsets.UTF_8);
                // Todoが空でない場合だけ新しいTodoを追加します。★変更
                if (!title.isEmpty()) {
                    // 次の番号のTodoを一覧に追加します。★変更
                    todos.add(new Todo(nextId, title));
                    // 次に使う番号を1つ進めます。★変更
                    nextId++;
                }
                // 戻り先をトップページにします。
                exchange.getResponseHeaders().set("Location", "/");
                // 303でトップページへの移動を伝えます。
                exchange.sendResponseHeaders(303, -1);
                // 応答を閉じます。
                exchange.close();
                // この分岐の処理を終えます。
                return;
            // トップページにフォームとTodo一覧を表示します。★変更
            } else if (path.equals("/")) {
                // ブラウザに返すHTML（ページの内容）を組み立てます。★変更
                String html = "<form method='post' action='/add'><input name='todo'><button>追加</button></form><ul>";
                // Todoを1件ずつページへ追加します。★変更
                for (Todo todo : todos) {
                    // 完了済みのTodoに付ける印を用意します。★変更
                    String mark = "";
                    // 完了済みならチェック印を付けます。★変更
                    if (todo.isDone()) {
                        mark = " ✔";
                    }
                    // Todoの題名と印を一覧へ足します。★変更
                    html += "<li>" + todo.getTitle() + mark + "</li>";
                }
                // Todo一覧のHTMLを閉じます。★変更
                html += "</ul>";
                // HTMLとUTF-8をブラウザに伝えます。★変更
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                // ページをUTF-8のデータに変換します。★変更
                byte[] body = html.getBytes("UTF-8");
                // 正常応答（200）とデータの長さを送ります。
                exchange.sendResponseHeaders(200, body.length);
                // ページのデータをブラウザへ送ります。
                exchange.getResponseBody().write(body);
                // ブラウザへの応答を閉じます。
                exchange.getResponseBody().close();
            // どの分岐にも当てはまらない場合の応答です。★変更
            } else {
                // 見つからないページの文字を用意します。★変更
                String message = "ページが見つかりません";
                // 返す文字がUTF-8（日本語にも使える文字コード）だと伝えます。
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                // 返す文字をUTF-8のデータに変換します。
                byte[] body = message.getBytes("UTF-8");
                // 正常応答（200）とデータの長さを送ります。
                exchange.sendResponseHeaders(200, body.length);
                // 文字のデータをブラウザへ送ります。
                exchange.getResponseBody().write(body);
                // ブラウザへの応答を閉じます。
                exchange.getResponseBody().close();
            }
        });
        // サーバーの待ち受けを始めます。
        server.start();
        // 起動したことと停止方法を表示します。
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）");
    }
}

// Todo1件の番号・内容・完了状態を持つクラスです。★変更
class Todo {
    // Todoの番号です。★変更
    private final int id;
    // Todoの内容です。★変更
    private final String title;
    // Todoが完了したかどうかです。★変更
    private boolean done;

    // Todoの番号と内容を受け取って作ります。★変更
    Todo(int id, String title) {
        this.id = id;
        this.title = title;
        this.done = false;
    }

    // Todoの番号を読み出します。★変更
    int getId() {
        return id;
    }

    // Todoの内容を読み出します。★変更
    String getTitle() {
        return title;
    }

    // Todoが完了済みか読み出します。★変更
    boolean isDone() {
        return done;
    }

    // Todoの完了状態を書き換えます。★変更
    void setDone(boolean done) {
        this.done = done;
    }
}