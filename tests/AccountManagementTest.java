import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Runs the real HTTP application in a temporary directory with a disposable database. */
public class AccountManagementTest {
    static int checks;
    static final String INITIAL = "Initial-password-123";
    static final String CHANGED = "Changed-password-456";
    static final String RESET = "Reset-password-789";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Path dir = Files.createTempDirectory("accounts-integration-");
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString()).reduce((a,b) -> a + java.io.File.pathSeparator + b).orElseThrow();
            int port;
            try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, "AccountManagementTest", "worker");
            process.directory(dir.toFile()).inheritIO();
            process.environment().put("APP_PORT", Integer.toString(port));
            process.environment().put("APP_ENV", "test");
            process.environment().put("APP_HTTPS_TERMINATED", "false");
            process.environment().put("APP_SESSION_IDLE_SECONDS", "1800");
            int status = process.start().waitFor();
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
            if (status != 0) throw new AssertionError("Integration checks failed");
            return;
        }
        try { run(); System.out.println("PASS: " + checks + " HTTP/database assertions"); System.exit(0); }
        catch (Throwable ex) { ex.printStackTrace(); System.exit(1); }
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        System.out.println("PASS: " + label);
    }

    static int number(String sql) throws Exception {
        try (Statement s = App.connection.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getInt(1); }
    }

    static String token(String html) {
        Matcher m = Pattern.compile("name='_csrf' value='([^']+)'").matcher(html);
        if (!m.find()) throw new AssertionError("CSRF input missing");
        return m.group(1);
    }

    static class Browser {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        String cookie = "";
        String csrf;
        HttpResponse<String> request(String path, Map<String,String> form) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + App.SERVER_PORT + path)).timeout(java.time.Duration.ofSeconds(10));
            if (!cookie.isEmpty()) builder.header("Cookie", cookie);
            if (form == null) builder.GET();
            else {
                String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).reduce((a,b) -> a + "&" + b).orElse("");
                builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            for (String value : response.headers().allValues("Set-Cookie")) cookie = value.split(";",2)[0];
            return response;
        }
        HttpResponse<String> get(String path) throws Exception { return request(path, null); }
        HttpResponse<String> post(String path, String... values) throws Exception {
            Map<String,String> form = new HashMap<>(); form.put("_csrf", csrf == null ? "" : csrf);
            for (int i=0; i<values.length; i+=2) form.put(values[i],values[i+1]);
            return request(path, form);
        }
        HttpResponse<String> login(String user, String password) throws Exception {
            csrf = token(get("/login").body());
            return post("/login", "username", user, "password", password);
        }
        void page(String path) throws Exception { var response = get(path); check(response.statusCode() == 200, "GET " + path); csrf = token(response.body()); }
    }

    static boolean redirects(HttpResponse<String> response, String path) {
        return response.statusCode() == 303 && response.headers().firstValue("Location").orElse("").equals(path);
    }

    static void run() throws Exception {
        // Seed a legacy database without the new column to exercise migration.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:todos.db"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, is_active INTEGER NOT NULL DEFAULT 1, list_title TEXT NOT NULL DEFAULT 'Wishes', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO users(username,display_name,password_hash,role) VALUES('admin','Admin',?,'admin')")) {
                p.setString(1,App.hashPassword(INITIAL,new byte[16])); p.executeUpdate();
            }
        }
        App.main(new String[0]);
        AccountManagement.ensureSchema();
        check(number("SELECT must_change_password FROM users WHERE username='admin'") == 0, "legacy migration preserves existing admin access and is repeatable");
        Browser guest = new Browser();
        check(redirects(guest.get("/admin/users"), "/login"), "anonymous admin URL requires login");
        check(redirects(guest.post("/admin/users/create", "username", "guest"), "/login"), "anonymous account creation denied");
        Browser admin = new Browser();
        check(redirects(admin.login("admin", INITIAL), "/"), "administrator login");
        admin.page("/admin/users");
        check(admin.post("/admin/users/create", "username", "bad", "displayName", "Bad", "password", "short").statusCode() == 400, "weak initial password rejected");
        check(admin.post("/admin/users/create", "_csrf", "wrong", "username", "bad", "displayName", "Bad", "password", INITIAL).statusCode() == 403, "creation requires valid CSRF");
        check(redirects(admin.post("/admin/users/create", "username", "member", "displayName", "<script>alert(1)</script>", "password", INITIAL, "role", "admin"), "/admin/users?notice=created"), "administrator creates user");
        check(number("SELECT COUNT(*) FROM users WHERE username='member' AND role='user' AND must_change_password=1") == 1, "role forgery ignored and first change required");
        check(number("SELECT COUNT(*) FROM users WHERE password_hash LIKE 'pbkdf2-sha256$%' AND username='member'") == 1, "initial password stored as hash");
        check(admin.get("/admin/users").body().contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "display names HTML escaped");
        check(admin.post("/admin/users/create", "username", "member", "displayName", "Duplicate", "password", INITIAL).statusCode() == 409, "duplicate user ID rejected");
        int id = number("SELECT id FROM users WHERE username='member'");
        String target = Integer.toString(id);
        Browser member = new Browser();
        check(redirects(member.login("member", INITIAL), "/"), "new user can authenticate");
        check(redirects(member.get("/"), "/account/password"), "first login requires password change");
        check(redirects(member.post("/save"), "/account/password"), "forced password change cannot be bypassed by POST");
        check(redirects(member.get("/admin/users"), "/account/password"), "forced user cannot bypass change with admin URL");
        member.page("/account/password");
        check(member.post("/account/password", "currentPassword", "incorrect", "password", CHANGED, "confirmPassword", CHANGED).statusCode() == 400, "own password change checks current password");
        check(member.post("/account/password", "currentPassword", INITIAL, "password", CHANGED, "confirmPassword", "different").statusCode() == 400, "password confirmation checked");
        check(member.post("/account/password", "_csrf", "wrong", "currentPassword", INITIAL, "password", CHANGED, "confirmPassword", CHANGED).statusCode() == 403, "password change CSRF protected");
        String oldCookie = member.cookie;
        check(redirects(member.post("/account/password", "currentPassword", INITIAL, "password", CHANGED, "confirmPassword", CHANGED), "/account/password?notice=changed"), "first password change succeeds");
        check(number("SELECT must_change_password FROM users WHERE username='member'") == 0, "first login restriction cleared");
        Browser stale = new Browser(); stale.cookie=oldCookie;
        check(redirects(stale.get("/"), "/login"), "old session invalidated after password change");
        member.page("/account/password");
        check(member.get("/").statusCode() == 200, "general user home available after password change");
        check(member.get("/admin/users").statusCode() == 403, "direct admin URL denied to general user");
        for (String path : List.of("/admin/users/create", "/admin/users/status", "/admin/users/reset-password"))
            check(member.post(path, "id", "1", "active", "0", "password", RESET).statusCode() == 403, "general user denied " + path);
        check(admin.post("/admin/users/status", "id", "1", "active", "0").statusCode() == 409, "last active admin cannot be disabled");
        check(number("SELECT is_active FROM users WHERE id=1") == 1, "last admin remains active");
        try (Statement s = App.connection.createStatement()) {
            s.executeUpdate("INSERT INTO todos(title,done,user_id,budget) VALUES('Preserved Wish',0," + id + ",1234)");
        }
        check(admin.post("/admin/users/status", "id", target, "active", "0", "_csrf", "wrong").statusCode() == 403, "status change CSRF protected");
        check(redirects(admin.post("/admin/users/status", "id", target, "active", "0"), "/admin/users?notice=status"), "administrator disables user");
        check(redirects(member.get("/"), "/login"), "disabling revokes existing user session");
        check(new Browser().login("member", CHANGED).statusCode() == 401, "disabled user cannot log in");
        check(number("SELECT COUNT(*) FROM todos WHERE user_id=" + id + " AND title='Preserved Wish' AND budget=1234") == 1, "Wish data retained after disabling");
        check(redirects(admin.post("/admin/users/status", "id", target, "active", "1"), "/admin/users?notice=status"), "administrator re-enables user");
        Browser otherSession = new Browser(); otherSession.login("member", CHANGED);
        check(admin.post("/admin/users/reset-password", "id", target, "password", RESET, "_csrf", "wrong").statusCode() == 403, "reset CSRF protected");
        check(redirects(admin.post("/admin/users/reset-password", "id", target, "password", RESET), "/admin/users?notice=reset"), "administrator resets password");
        check(redirects(otherSession.get("/"), "/login"), "reset revokes existing session");
        check(new Browser().login("member", CHANGED).statusCode() == 401, "old password rejected after reset");
        member = new Browser();
        check(redirects(member.login("member", RESET), "/"), "reset password authenticates");
        check(redirects(member.get("/"), "/account/password"), "reset requires password change again");
        member.page("/account/password");
        check(redirects(member.post("/account/password", "currentPassword", RESET, "password", CHANGED, "confirmPassword", CHANGED), "/account/password?notice=changed"), "user changes reset password");
        member.page("/account/password");
        check(redirects(member.post("/account/password", "currentPassword", CHANGED, "password", INITIAL, "confirmPassword", INITIAL), "/account/password?notice=changed"), "general user can voluntarily change own password");
        Browser regression = new Browser();
        regression.csrf=token(regression.get("/login").body());
        check(regression.get("/favicon.ico").statusCode() == 204, "favicon does not redirect to login");
        regression.get("/missing-resource");
        regression.get("/login");
        check(redirects(regression.post("/login", "username", "member", "password", INITIAL), "/"), "login session survives auxiliary requests");
        check(number("SELECT COUNT(*) FROM todos WHERE user_id=" + id) == 1, "Wish retained after complete account lifecycle");
    }
}
