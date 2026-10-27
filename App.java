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
    // プログラムの開始地点です。
    public static void main(String[] args) throws Exception {
        // ポート8080（通信の入口番号）でサーバーを用意します。
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // Todoを保存する空の一覧を一度だけ作ります。
        List<String> todos = new ArrayList<>();
        // 「/」（トップページ）へのアクセスを処理します。
        server.createContext("/", exchange -> {
            // アクセスされたパス（URLの末尾部分）を取り出します。
            String path = exchange.getRequestURI().getPath();
            // HTTPメソッド（GETやPOSTなどの要求方法）を取り出します。
            String method = exchange.getRequestMethod();
            // 返す文字を入れる変数を用意します。
            String message;
            // POSTで/addに来た内容をTodo一覧に追加します。
            if (path.equals("/add") && method.equals("POST")) {
                // 送られてきた本文をUTF-8で読み取ります。
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // todo=の後ろを切り出します。
                String value = body.substring(5);
                // URLデコードして日本語を元に戻します。
                String todo = URLDecoder.decode(value, StandardCharsets.UTF_8);
                // Todoが空でない場合だけ一覧に追加します。
                if (!todo.isEmpty()) {
                    todos.add(todo);
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
                // ブラウザに返すHTML（ページの内容）を組み立てます。
                message = "<form method='post' action='/add'><input name='todo'><button>追加</button></form><ul>";
                // Todoを順番にページへ追加します。
                for (String todo : todos) {
                    message += "<li>" + todo + "</li>";
                }
                // Todo一覧のHTMLを閉じます。
                message += "</ul>";
                // HTMLとUTF-8をブラウザに伝えます。
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                // ページをUTF-8のデータに変換します。
                byte[] body = message.getBytes("UTF-8");
                // 正常応答（200）とデータの長さを送ります。
                exchange.sendResponseHeaders(200, body.length);
                // ページのデータをブラウザへ送ります。
                exchange.getResponseBody().write(body);
                // ブラウザへの応答を閉じます。
                exchange.getResponseBody().close();
            // /hello かどうかを比べます。
            } else if (path.equals("/hello")) {
                // URLのクエリ（?の後ろの文字列）を取り出します。
                String query = exchange.getRequestURI().getRawQuery();
                // 値がない場合は、そのことを返します。
                if (query == null || query.length() <= 5) {
                    message = "値がありません";
                // name=の後ろを切り出し、URLデコードして名前にします。
                } else {
                    String name = URLDecoder.decode(query.substring(5), "UTF-8");
                    // 名前を挨拶の文に加えます。
                    message = "こんにちは、" + name + "さん！";
                }
            // /bye かどうかを比べます。
            } else if (path.equals("/bye")) {
                message = "さようなら！";
            // どちらにも当てはまらない場合の返す文字です。
            } else {
                message = "ページが見つかりません";
            }
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
        });
        // サーバーの待ち受けを始めます。
        server.start();
        // 起動したことと停止方法を表示します。
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）");
    }
}
