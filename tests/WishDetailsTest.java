import java.io.File;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Migration, validation, priority ordering and owner isolation over real HTTP. */
public class WishDetailsTest {
    static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try { worker(args[0]); System.out.println("PASS: " + checks + " STEP 11 assertions in " + args[0]); System.exit(0); }
            catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        }
        Path directory = Files.createTempDirectory("wish-details-");
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).reduce((a,b) -> a + File.pathSeparator + b).orElseThrow();
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        try {
            for (String phase : List.of("first", "restart")) {
                ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, "WishDetailsTest", phase);
                process.directory(directory.toFile()).inheritIO();
                process.environment().put("APP_PORT", Integer.toString(port));
                process.environment().put("APP_ENV", "test");
                if (process.start().waitFor() != 0) throw new AssertionError(phase + " failed");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS: " + label);
    }

    static void worker(String phase) throws Exception {
        if (phase.equals("first")) seedLegacyWish();
        App.main(new String[0]);
        WishDetails.ensureSchema(App.connection);
        WishOwnershipTest.Browser a = new WishOwnershipTest.Browser();
        WishOwnershipTest.Browser b = new WishOwnershipTest.Browser();
        check(WishOwnershipTest.redirect(a.login("userA"), "/") && WishOwnershipTest.redirect(b.login("userB"), "/"), "two users log in");
        a.home(); b.home();
        if (phase.equals("first")) first(a,b); else restart(a,b);
    }

    static void seedLegacyWish() throws Exception {
        WishOwnershipTest.seed();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:todos.db"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE todos(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER NOT NULL, due_date TEXT, budget INTEGER, completed_date TEXT, completed_amount INTEGER, user_id INTEGER REFERENCES users(id))");
            statement.executeUpdate("INSERT INTO todos(title,done,due_date,budget,user_id) VALUES('Legacy A',0,'2027-01-01',250,(SELECT id FROM users WHERE username='userA'))");
        }
    }

    static int number(String sql) throws Exception {
        try (Statement statement = App.connection.createStatement(); ResultSet row = statement.executeQuery(sql)) { row.next(); return row.getInt(1); }
    }

    static String value(String title, String column) throws Exception {
        try (PreparedStatement statement = App.connection.prepareStatement("SELECT " + column + " FROM todos WHERE title = ?")) {
            statement.setString(1, title);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getString(1) : null; }
        }
    }

    static Map<String,String> wish(String title, String priority) {
        Map<String,String> fields = WishOwnershipTest.fields(title, "100");
        if (priority != null) fields.put("priority", priority);
        return fields;
    }

    static void first(WishOwnershipTest.Browser a, WishOwnershipTest.Browser b) throws Exception {
        check(number("SELECT COUNT(*) FROM pragma_table_info('todos') WHERE name IN ('location_name','address','latitude','longitude','memo','priority')") == 6, "six columns migrated once");
        check(number("SELECT priority FROM todos WHERE title='Legacy A'") == 3 && number("SELECT budget FROM todos WHERE title='Legacy A'") == 250, "legacy Wish preserved with default priority");
        check(value("Legacy A", "memo") == null && value("Legacy A", "latitude") == null, "optional legacy values are null");
        Map<String,String> full = wish("A full", "5");
        full.put("locationName", "京都駅"); full.put("address", "京都市下京区");
        full.put("latitude", "35.0"); full.put("longitude", "135.75");
        full.put("memo", "一行目\n二行目 <private>");
        check(WishOwnershipTest.redirect(a.wish("/save", full, null), "/"), "A registers all fields");
        check("京都駅".equals(value("A full", "location_name")) && "京都市下京区".equals(value("A full", "address")), "location and address saved");
        check("35.0".equals(value("A full", "latitude")) && "135.75".equals(value("A full", "longitude")), "coordinates saved");
        check("一行目\n二行目 <private>".equals(value("A full", "memo")) && number("SELECT priority FROM todos WHERE title='A full'") == 5, "multiline memo and priority saved");
        String aHtml = a.body(a.get("/"));
        check(aHtml.contains("data-location-name='京都駅'") && aHtml.contains("data-memo='一行目\n二行目 &lt;private&gt;'"), "editing data is present only on owner card and HTML escaped");
        check(aHtml.contains("★★★★★") && aHtml.contains("height") == false, "priority stars render without inline card height changes");
        check(aHtml.contains("id='wishLocationName'") && aHtml.contains("id='wishMemo'") && aHtml.contains("name='priority'"), "common Wish modal exposes new fields");
        Map<String,String> privateB = wish("B private", "1");
        privateB.put("locationName", "秘密の場所"); privateB.put("memo", "Bだけの秘密メモ");
        check(WishOwnershipTest.redirect(b.wish("/save", privateB, null), "/"), "B registers separate Wish");
        check(!a.body(a.get("/")).contains("Bだけの秘密メモ") && !a.body(a.get("/")).contains("秘密の場所"), "A cannot read B location or memo");
        check(!b.body(b.get("/")).contains("京都駅") && !b.body(b.get("/")).contains("一行目"), "B cannot read A details");
        int bId = WishOwnershipTest.id("B private");
        Map<String,String> attack = wish("stolen", "5"); attack.put("id", Integer.toString(bId)); attack.put("memo", "overwritten");
        check(a.wish("/save", attack, null).statusCode() == 404 && a.wish("/edit", attack, null).statusCode() == 404, "A cannot edit B details through either URL");
        check("Bだけの秘密メモ".equals(value("B private", "memo")), "B memo unchanged after attack");
        check(a.post("/delete", "id", Integer.toString(bId)).statusCode() == 404, "A cannot delete B details");
        check(a.get("/edit?id=" + bId).statusCode() == 303 && !a.body(a.get("/")).contains("Bだけの秘密メモ"), "direct edit URL reveals no foreign details");
        Map<String,String> edited = wish("A full", "4"); edited.put("id", Integer.toString(WishOwnershipTest.id("A full")));
        edited.put("locationName", "大阪駅"); edited.put("address", "大阪市北区"); edited.put("latitude", "-90"); edited.put("longitude", "180"); edited.put("memo", "編集したメモ\n二行目");
        edited.put("completedDate", "2027-01-02"); edited.put("completedAmount", "80");
        check(WishOwnershipTest.redirect(a.wish("/save", edited, null), "/"), "owner edits details and completion");
        check("大阪駅".equals(value("A full", "location_name")) && "-90.0".equals(value("A full", "latitude")) && "180.0".equals(value("A full", "longitude")), "edited location and boundary coordinates saved");
        check("編集したメモ\n二行目".equals(value("A full", "memo")) && number("SELECT priority FROM todos WHERE title='A full'") == 4, "edited memo and priority saved");
        check(number("SELECT done FROM todos WHERE title='A full'") == 1 && "80".equals(value("A full", "completed_amount")), "completion data remains functional");
        Map<String,String> blank = wish("A blank", null);
        check(WishOwnershipTest.redirect(a.wish("/save", blank, null), "/"), "empty optional fields accepted");
        check(value("A blank", "location_name") == null && value("A blank", "address") == null && value("A blank", "latitude") == null && value("A blank", "longitude") == null && value("A blank", "memo") == null, "empty optional fields stored as null");
        check(number("SELECT priority FROM todos WHERE title='A blank'") == 3, "omitted priority defaults to 3");
        for (int priority=1; priority<=5; priority++) {
            String title = "A priority " + priority;
            check(WishOwnershipTest.redirect(a.wish("/save", wish(title, Integer.toString(priority)), null), "/") && number("SELECT priority FROM todos WHERE title='" + title + "'") == priority, "priority " + priority + " saved");
        }
        String beforeInvalid = value("A full", "memo");
        for (String[] invalid : new String[][] {{"latitude","90.001"},{"latitude","NaN"},{"longitude","-180.001"},{"longitude","Infinity"},{"priority","0"},{"priority","6"},{"priority","x"}}) {
            Map<String,String> bad = wish("A full", "3"); bad.put("id", Integer.toString(WishOwnershipTest.id("A full"))); bad.put("memo", "should not save"); bad.put(invalid[0], invalid[1]);
            check(a.wish("/save", bad, null).statusCode() == 400, "invalid " + invalid[0] + "=" + invalid[1] + " rejected");
        }
        check(beforeInvalid.equals(value("A full", "memo")), "invalid edits do not modify existing Wish");
        String sorted = a.body(a.get("/?sort=priority"));
        check(sorted.indexOf("A priority 5") < sorted.indexOf("A priority 4") && sorted.indexOf("A priority 4") < sorted.indexOf("A priority 3") && sorted.indexOf("A priority 3") < sorted.indexOf("A priority 2") && sorted.indexOf("A priority 2") < sorted.indexOf("A priority 1"), "priority sorting descending");
        check(sorted.indexOf("Legacy A") < sorted.indexOf("A blank") && sorted.indexOf("A blank") < sorted.indexOf("A priority 3"), "same priority retains existing ID order");
        check(!sorted.contains("B private"), "sorted view remains user scoped");
        check(a.body(a.get("/")).indexOf("A priority 1") < a.body(a.get("/")).indexOf("A full"), "default ordering keeps pending before complete");
    }

    static void restart(WishOwnershipTest.Browser a, WishOwnershipTest.Browser b) throws Exception {
        check(number("SELECT COUNT(*) FROM pragma_table_info('todos') WHERE name IN ('location_name','address','latitude','longitude','memo','priority')") == 6, "migration idempotent after restart");
        check("大阪駅".equals(value("A full", "location_name")) && "編集したメモ\n二行目".equals(value("A full", "memo")) && number("SELECT priority FROM todos WHERE title='A full'") == 4, "new details persist after restart");
        check(number("SELECT priority FROM todos WHERE title='Legacy A'") == 3 && number("SELECT budget FROM todos WHERE title='Legacy A'") == 250, "legacy data survives restart");
        check(a.body(a.get("/?sort=priority")).contains("大阪駅") && !a.body(a.get("/?sort=priority")).contains("Bだけの秘密メモ"), "A data isolated after restart");
        check(b.body(b.get("/")).contains("Bだけの秘密メモ") && !b.body(b.get("/")).contains("編集したメモ"), "B data isolated after restart");
        int bId = WishOwnershipTest.id("B private");
        check(a.post("/delete", "id", Integer.toString(bId)).statusCode() == 404 && "Bだけの秘密メモ".equals(value("B private", "memo")), "foreign deletion denied after restart");
        check(WishOwnershipTest.redirect(b.post("/delete", "id", Integer.toString(bId)), "/") && WishOwnershipTest.id("B private") == -1, "owner deletion works after restart");
    }
}
