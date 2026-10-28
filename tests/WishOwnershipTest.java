import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** End-to-end ownership checks against an isolated database and HTTP server. */
public class WishOwnershipTest {
    static final String PASSWORD = "Account-password-123";
    static int checks;
    static final byte[] PNG = {(byte)0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS: " + label);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try { worker(args[0]); System.out.println("PASS: " + checks + " ownership assertions in " + args[0]); System.exit(0); }
            catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        }
        Path dir = Files.createTempDirectory("wish-ownership-");
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(p -> Path.of(p).toAbsolutePath().toString()).reduce((a,b) -> a + File.pathSeparator + b).orElseThrow();
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        try {
            for (String phase : List.of("first", "restart")) {
                ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, "WishOwnershipTest", phase);
                process.directory(dir.toFile()).inheritIO();
                process.environment().put("APP_PORT", Integer.toString(port));
                process.environment().put("APP_ENV", "test");
                int status = process.start().waitFor();
                if (status != 0) throw new AssertionError(phase + " phase failed");
            }
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    static void worker(String phase) throws Exception {
        if (phase.equals("first")) seed();
        App.main(new String[0]);
        AccountManagement.ensureSchema();
        Browser a = new Browser(), b = new Browser();
        check(redirect(a.login("userA"), "/"), "A login");
        check(redirect(b.login("userB"), "/"), "B login");
        a.home(); b.home();
        if (phase.equals("first")) first(a, b);
        else restarted(a, b);
    }

    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:todos.db"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, is_active INTEGER NOT NULL DEFAULT 1, list_title TEXT NOT NULL DEFAULT 'My Wishes', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO users(username, display_name, password_hash, role) VALUES(?,?,?,'user')")) {
                for (String name : List.of("userA", "userB")) {
                    p.setString(1, name); p.setString(2, name); p.setString(3, App.hashPassword(PASSWORD, salt())); p.executeUpdate();
                }
            }
        }
    }

    static byte[] salt() { byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt); return salt; }
    static boolean redirect(HttpResponse<?> response, String path) { return response.statusCode() == 303 && response.headers().firstValue("Location").orElse("").equals(path); }

    static int id(String title) throws Exception {
        try (PreparedStatement s = App.connection.prepareStatement("SELECT id FROM todos WHERE title = ?")) {
            s.setString(1, title); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : -1; }
        }
    }

    static int owner(int id) throws Exception {
        try (PreparedStatement s = App.connection.prepareStatement("SELECT user_id FROM todos WHERE id = ?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : -1; }
        }
    }

    static String imagePath(int id) throws Exception {
        try (PreparedStatement s = App.connection.prepareStatement("SELECT image_path FROM todos WHERE id = ?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    static int userId(String name) throws Exception {
        try (PreparedStatement s = App.connection.prepareStatement("SELECT id FROM users WHERE username = ?")) {
            s.setString(1, name); try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    static class Browser {
        final HttpClient client = HttpClient.newHttpClient();
        String cookie = "", csrf = "";
        HttpResponse<byte[]> request(String path, String method, byte[] body, String type) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + App.SERVER_PORT + path)).timeout(java.time.Duration.ofSeconds(20));
            if (!cookie.isEmpty()) builder.header("Cookie", cookie);
            if (type != null) builder.header("Content-Type", type);
            if (method.equals("POST")) builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)); else builder.GET();
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            for (String value : response.headers().allValues("Set-Cookie")) cookie = value.split(";",2)[0];
            return response;
        }
        HttpResponse<byte[]> get(String path) throws Exception { return request(path, "GET", null, null); }
        String body(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
        HttpResponse<byte[]> post(String path, String... pairs) throws Exception {
            Map<String,String> form = new LinkedHashMap<>(); form.put("_csrf", csrf);
            for (int i=0; i<pairs.length; i+=2) form.put(pairs[i], pairs[i+1]);
            String content = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).reduce((x,y) -> x + "&" + y).orElse("");
            return request(path, "POST", content.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
        }
        HttpResponse<byte[]> login(String name) throws Exception {
            Matcher m = Pattern.compile("name='_csrf' value='([^']+)'").matcher(body(get("/login")));
            if (!m.find()) throw new AssertionError("login CSRF missing");
            csrf=m.group(1);
            return post("/login", "username", name, "password", PASSWORD);
        }
        void home() throws Exception {
            var response = get("/");
            if (response.statusCode() != 200) throw new AssertionError("home HTTP " + response.statusCode());
            Matcher m = Pattern.compile("name='_csrf' value='([^']+)'").matcher(body(response));
            if (!m.find()) throw new AssertionError("home CSRF missing");
            csrf=m.group(1);
        }
        HttpResponse<byte[]> wish(String path, Map<String,String> fields, byte[] image) throws Exception {
            String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Map<String,String> form = new LinkedHashMap<>(); form.put("_csrf", csrf); form.putAll(fields);
            for (var field : form.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            if (image != null) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(image); out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return request(path, "POST", out.toByteArray(), "multipart/form-data; boundary=" + boundary);
        }
    }

    static Map<String,String> fields(String title, String budget) {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("title", title); f.put("categoryId", "1"); f.put("dueDate", "2027-01-01"); f.put("budget", budget); f.put("user_id", "999999");
        return f;
    }

    static void first(Browser a, Browser b) throws Exception {
        check(redirect(a.wish("/save", fields("A pending", "100"), PNG), "/"), "A creates Wish with image");
        check(redirect(a.wish("/save", fields("A done", "200"), null), "/"), "A creates second Wish");
        check(redirect(b.wish("/save", fields("B pending", "700"), PNG), "/"), "B creates Wish with image");
        int aPending = id("A pending"), aDone = id("A done"), bPending = id("B pending");
        check(aPending > 0 && aDone > 0 && bPending > 0, "all Wish IDs persisted");
        check(owner(aPending) == userId("userA") && owner(aDone) == userId("userA") && owner(bPending) == userId("userB"), "submitted user_id ignored");
        Map<String,String> completed = fields("A done", "200");
        completed.put("id", Integer.toString(aDone)); completed.put("completedDate", "2027-01-02"); completed.put("completedAmount", "180");
        check(redirect(a.wish("/save", completed, null), "/"), "A marks own Wish complete");
        String aHtml = a.body(a.get("/")), bHtml = b.body(b.get("/"));
        check(aHtml.contains("A pending") && aHtml.contains("A done") && !aHtml.contains("B pending"), "A list excludes B Wish");
        check(bHtml.contains("B pending") && !bHtml.contains("A pending") && !bHtml.contains("A done"), "B list excludes A Wishes");
        check(aHtml.contains("50%") && aHtml.contains("100円"), "A progress and pending budget use A Wishes");
        check(bHtml.contains("0%") && bHtml.contains("700円"), "B progress and pending budget use B Wishes");
        check(aHtml.contains("達成日: 2027-01-02") && aHtml.contains("180円"), "completed details preserved");
        String aImage = imagePath(aPending), bImage = imagePath(bPending);
        check(aImage != null && bImage != null && !aImage.equals(bImage), "each image stored separately");
        check(a.get("/" + aImage).statusCode() == 200 && b.get("/" + bImage).statusCode() == 200, "owners can fetch own images");
        check(a.get("/" + bImage).statusCode() == 404 && b.get("/" + aImage).statusCode() == 404, "cross-user image access denied");
        check(a.get("/uploads/wishes/not-linked.png").statusCode() == 404, "orphan image path denied");
        check(a.get("/uploads/wishes/../" + Path.of(bImage).getFileName()).statusCode() != 200, "image path traversal denied");
        Map<String,String> attack = fields("A overwrote B", "0"); attack.put("id", Integer.toString(bPending)); attack.put("removeImage", "on");
        check(a.wish("/save", attack, null).statusCode() == 404, "A cannot edit B by Wish ID");
        check(a.wish("/edit", attack, PNG).statusCode() == 404, "alternate edit URL cannot replace B image");
        check(id("B pending") == bPending && imagePath(bPending).equals(bImage) && Files.isRegularFile(Path.of(bImage)), "B Wish and image unchanged after edit attacks");
        check(a.post("/delete", "id", Integer.toString(bPending)).statusCode() == 404, "A cannot delete B by Wish ID");
        check(id("B pending") == bPending && Files.isRegularFile(Path.of(bImage)), "B Wish and image unchanged after delete attack");
        check(b.post("/delete", "id", Integer.toString(aDone)).statusCode() == 404, "B cannot delete A by Wish ID");
        check(a.wish("/save", fields("Forged owner", "12"), null).statusCode() == 303, "new Wish ignores submitted owner field");
        check(owner(id("Forged owner")) == userId("userA"), "forged owner saved as authenticated user");
        check(redirect(a.post("/settings/title", "title", "A's list"), "/"), "A changes own list title");
        check(redirect(b.post("/settings/title", "title", "B's list"), "/"), "B changes own list title");
        check(a.body(a.get("/")).contains("A&#39;s list") && !a.body(a.get("/")).contains("B&#39;s list"), "A title isolated");
        check(b.body(b.get("/")).contains("B&#39;s list") && !b.body(b.get("/")).contains("A&#39;s list"), "B title isolated");
        check(a.get("/assets/categories/no-image.png").statusCode() == 404, "category image route remains available with normal validation");
    }

    static void restarted(Browser a, Browser b) throws Exception {
        check(owner(id("A pending")) == userId("userA") && owner(id("B pending")) == userId("userB"), "Wish ownership persists after restart");
        String aHtml = a.body(a.get("/")), bHtml = b.body(b.get("/"));
        check(aHtml.contains("A pending") && !aHtml.contains("B pending"), "A list remains isolated after restart");
        check(bHtml.contains("B pending") && !bHtml.contains("A pending"), "B list remains isolated after restart");
        check(aHtml.contains("A&#39;s list") && bHtml.contains("B&#39;s list"), "per-user titles persist after restart");
        check(aHtml.contains("33%") && aHtml.contains("112円"), "A metrics persist after restart");
        check(bHtml.contains("0%") && bHtml.contains("700円"), "B metrics persist after restart");
        check(a.get("/" + imagePath(id("B pending"))).statusCode() == 404, "image authorization persists after restart");
        check(b.get("/" + imagePath(id("B pending"))).statusCode() == 200, "owner image remains available after restart");
        int own = id("Forged owner");
        check(redirect(a.post("/delete", "id", Integer.toString(own)), "/"), "A deletes own Wish");
        check(id("Forged owner") == -1, "own Wish delete persisted");
        int bId = id("B pending"); String image = imagePath(bId);
        check(redirect(b.post("/delete", "id", Integer.toString(bId)), "/"), "B deletes own Wish");
        check(id("B pending") == -1 && !Files.exists(Path.of(image)), "own image removed with Wish");
    }
}
