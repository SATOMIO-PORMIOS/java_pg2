import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Map;

/** Account routes share the application's authentication and CSRF protection. */
final class AccountManagement {
    static void ensureSchema() throws SQLException {
        boolean present = false;
        try (Statement s = App.connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA table_info(users)")) {
            while (r.next()) if ("must_change_password".equals(r.getString("name"))) present = true;
        }
        if (!present) try (Statement s = App.connection.createStatement()) {
            s.executeUpdate("ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0 CHECK(must_change_password IN (0,1))");
        }
    }

    static boolean mustChangePassword(int userId) throws SQLException {
        try (PreparedStatement s = App.connection.prepareStatement("SELECT must_change_password FROM users WHERE id = ?")) {
            s.setInt(1, userId);
            try (ResultSet r = s.executeQuery()) { return r.next() && r.getInt(1) == 1; }
        }
    }

    static boolean isAdmin(int userId) throws SQLException {
        try (PreparedStatement s = App.connection.prepareStatement("SELECT 1 FROM users WHERE id = ? AND role = 'admin' AND is_active = 1")) {
            s.setInt(1, userId);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    static boolean handle(HttpExchange e, AuthSession session) throws IOException, SQLException {
        String path = e.getRequestURI().getPath();
        if (!path.equals("/account/password") && !path.equals("/admin") && !path.startsWith("/admin/")) return false;
        boolean adminRoute = path.equals("/admin") || path.startsWith("/admin/");
        if (adminRoute && !isAdmin(session.userId)) {
            App.sendText(e, 403, "管理者権限が必要です。");
            return true;
        }
        if ("GET".equals(e.getRequestMethod())) {
            if (path.equals("/account/password")) passwordPage(e, session, 200, "");
            else if (path.equals("/admin/users")) usersPage(e, session, 200, "");
            else App.sendText(e, 404, "ページが見つかりません。");
            return true;
        }
        if (!"POST".equals(e.getRequestMethod())) {
            e.getResponseHeaders().set("Allow", "GET, POST");
            App.sendText(e, 405, "この操作には対応していません。");
            return true;
        }
        Map<String, String> form;
        try { form = App.parseForm(new String(App.readLimited(e.getRequestBody(), 8192), StandardCharsets.UTF_8)); }
        catch (IOException ex) { App.sendText(e, 413, "入力が長すぎます。"); return true; }
        catch (IllegalArgumentException ex) { App.sendText(e, 400, "入力形式が不正です。"); return true; }
        if (!App.verifyCsrf(e, session, form.get("_csrf"))) return true;
        if (path.equals("/account/password")) changePassword(e, session, form);
        else if (path.equals("/admin/users/create")) createUser(e, session, form);
        else if (path.equals("/admin/users/status")) changeStatus(e, session, form);
        else if (path.equals("/admin/users/reset-password")) resetPassword(e, session, form);
        else App.sendText(e, 404, "ページが見つかりません。");
        return true;
    }

    static String hash(String password) throws IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try { return App.hashPassword(password, salt); }
        catch (Exception ex) { throw new IOException("Password hashing failed", ex); }
    }

    static boolean validPassword(String password) { return password.length() >= 12 && password.length() <= 128 && !password.isBlank(); }

    static void createUser(HttpExchange e, AuthSession session, Map<String, String> form) throws IOException, SQLException {
        String username = form.getOrDefault("username", "").trim();
        String displayName = form.getOrDefault("displayName", "").trim();
        String password = form.getOrDefault("password", "");
        if (!username.matches("[A-Za-z0-9_.-]{1,100}") || displayName.isEmpty() || displayName.length() > 100 || !validPassword(password)) {
            usersPage(e, session, 400, "ユーザーIDは半角英数字・_・.・-で100文字以内、表示名は1〜100文字、パスワードは12〜128文字で入力してください。");
            return;
        }
        // The role is deliberately fixed on the server; submitted role fields are ignored.
        try (PreparedStatement s = App.connection.prepareStatement("INSERT INTO users(username, display_name, password_hash, role, must_change_password) VALUES(?, ?, ?, 'user', 1) ON CONFLICT(username) DO NOTHING")) {
            s.setString(1, username); s.setString(2, displayName); s.setString(3, hash(password));
            if (s.executeUpdate() == 0) { usersPage(e, session, 409, "このユーザーIDは既に登録されています。"); return; }
        }
        redirect(e, "/admin/users?notice=created");
    }

    static int targetId(Map<String, String> form) {
        try { int id = Integer.parseInt(form.getOrDefault("id", "")); return id > 0 ? id : -1; }
        catch (NumberFormatException ex) { return -1; }
    }

    static void changeStatus(HttpExchange e, AuthSession session, Map<String, String> form) throws IOException, SQLException {
        int id = targetId(form);
        String active = form.getOrDefault("active", "");
        if (id < 1 || !(active.equals("0") || active.equals("1"))) { usersPage(e, session, 400, "対象ユーザーまたは状態が不正です。"); return; }
        // A single conditional UPDATE protects the last administrator atomically.
        try (PreparedStatement s = App.connection.prepareStatement("UPDATE users SET is_active = ? WHERE id = ? AND (? = 1 OR role <> 'admin' OR is_active = 0 OR (SELECT COUNT(*) FROM users WHERE role = 'admin' AND is_active = 1) > 1)")) {
            s.setInt(1, Integer.parseInt(active)); s.setInt(2, id); s.setInt(3, Integer.parseInt(active));
            if (s.executeUpdate() == 0) { usersPage(e, session, 409, "最後の有効な管理者は無効化できません。または対象ユーザーが存在しません。"); return; }
        }
        if (active.equals("0")) invalidateSessions(id);
        if (id == session.userId && active.equals("0")) { App.clearSessionCookie(e); redirect(e, "/login"); }
        else redirect(e, "/admin/users?notice=status");
    }

    static void resetPassword(HttpExchange e, AuthSession session, Map<String, String> form) throws IOException, SQLException {
        int id = targetId(form);
        String password = form.getOrDefault("password", "");
        if (id < 1 || !validPassword(password)) { usersPage(e, session, 400, "対象ユーザーを確認し、パスワードを12〜128文字で入力してください。"); return; }
        try (PreparedStatement s = App.connection.prepareStatement("UPDATE users SET password_hash = ?, must_change_password = 1 WHERE id = ?")) {
            s.setString(1, hash(password)); s.setInt(2, id);
            if (s.executeUpdate() == 0) { usersPage(e, session, 404, "対象ユーザーが存在しません。"); return; }
        }
        invalidateSessions(id);
        if (id == session.userId) { App.clearSessionCookie(e); redirect(e, "/login"); }
        else redirect(e, "/admin/users?notice=reset");
    }

    static void changePassword(HttpExchange e, AuthSession session, Map<String, String> form) throws IOException, SQLException {
        String current = form.getOrDefault("currentPassword", "");
        String password = form.getOrDefault("password", "");
        if (current.length() > 1024 || !validPassword(password) || !password.equals(form.get("confirmPassword")) || password.equals(current)) {
            passwordPage(e, session, 400, "新しいパスワードは現在と異なる12〜128文字とし、確認欄にも同じ値を入力してください。"); return;
        }
        String attemptKey = "password-change:" + session.userId;
        if (App.isRateLimited(attemptKey)) { passwordPage(e, session, 429, "試行回数が上限に達しました。15分後に再度お試しください。"); return; }
        String oldHash;
        String role;
        try (PreparedStatement s = App.connection.prepareStatement("SELECT password_hash, role FROM users WHERE id = ? AND is_active = 1")) {
            s.setInt(1, session.userId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { App.sendText(e, 403, "このアカウントは利用できません。"); return; }
                oldHash = r.getString(1); role = r.getString(2);
            }
        }
        boolean matches;
        try { matches = App.verifyPassword(current, oldHash); }
        catch (Exception ex) { throw new IOException("Password verification failed", ex); }
        if (!matches) { App.recordLoginFailure(attemptKey); passwordPage(e, session, 400, "現在のパスワードを確認してください。"); return; }
        try (PreparedStatement s = App.connection.prepareStatement("UPDATE users SET password_hash = ?, must_change_password = 0 WHERE id = ? AND password_hash = ? AND is_active = 1")) {
            s.setString(1, hash(password)); s.setInt(2, session.userId); s.setString(3, oldHash);
            if (s.executeUpdate() != 1) { App.sendText(e, 409, "アカウントが更新されました。再ログインしてください。"); return; }
        }
        invalidateSessions(session.userId);
        synchronized (App.LOGIN_ATTEMPTS) { App.LOGIN_ATTEMPTS.remove(attemptKey); }
        AuthSession next = App.createSession(session.userId, role, App.SESSION_IDLE_TIMEOUT_MS);
        e.getResponseHeaders().remove("Set-Cookie");
        App.setSessionCookie(e, next, App.SESSION_IDLE_TIMEOUT_MS);
        redirect(e, "/account/password?notice=changed");
    }

    static void invalidateSessions(int userId) { App.SESSIONS.entrySet().removeIf(entry -> entry.getValue().userId == userId); }

    static void redirect(HttpExchange e, String location) throws IOException {
        e.getResponseHeaders().set("Location", location);
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.sendResponseHeaders(303, -1); e.close();
    }

    static String csrf(AuthSession session) { return "<input type='hidden' name='_csrf' value='" + session.csrfToken + "'>"; }

    static String input(String name, String label, String type, String constraints) {
        return "<label>" + label + "<input name='" + name + "' type='" + type + "' " + constraints + " required></label>";
    }

    static String passwordInput(String name, String label) {
        return input(name, label, "password", "autocomplete='new-password' minlength='12' maxlength='128'");
    }

    static String page(String title, AuthSession session, boolean admin, String content) {
        return "<!doctype html><html lang='ja'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>" + title
            + "</title><link rel='stylesheet' href='/assets/app.css'></head><body class='account-page'><header class='app-header'><h1>" + title
            + "</h1><div class='account-actions'><nav class='account-nav'><a href='/'>ホーム</a>" + (admin ? "<a href='/admin/users'>ユーザー管理</a>" : "")
            + "<a href='/account/password'>パスワード変更</a></nav><form class='logout-form' method='post' action='/logout'>" + csrf(session)
            + "<button>ログアウト</button></form></div></header><main>" + content + "</main></body></html>";
    }

    static String message(HttpExchange e, String error) {
        if (!error.isEmpty()) return "<p class='login-error' role='alert'>" + App.escapeHtml(error) + "</p>";
        String query = e.getRequestURI().getRawQuery();
        String text = switch (query == null ? "" : query) {
            case "notice=created" -> "ユーザーを登録しました。初期パスワードを本人に安全な方法で伝えてください。";
            case "notice=status" -> "アカウントの状態を更新しました。";
            case "notice=reset" -> "パスワードを再設定しました。次回ログイン時に変更が必要です。";
            case "notice=changed" -> "パスワードを変更しました。ホームから利用を続けられます。";
            default -> "";
        };
        return text.isEmpty() ? "" : "<p class='account-notice' role='status'>" + text + "</p>";
    }

    static void passwordPage(HttpExchange e, AuthSession session, int status, String error) throws IOException, SQLException {
        String content = message(e, error) + "<section class='account-panel'><h2>パスワード変更</h2>"
            + (mustChangePassword(session.userId) ? "<p role='status'>初期・再設定パスワードの変更が必要です。変更が完了するまで他の機能は利用できません。</p>" : "")
            + "<p>新しいパスワードは12〜128文字で設定してください。</p><form class='account-form' method='post' action='/account/password'>" + csrf(session)
            + input("currentPassword", "現在のパスワード", "password", "autocomplete='current-password' maxlength='1024'")
            + passwordInput("password", "新しいパスワード") + passwordInput("confirmPassword", "新しいパスワード（確認）")
            + "<button>パスワードを変更</button></form></section>";
        App.sendHtmlStatus(e, status, page("パスワード変更", session, isAdmin(session.userId), content));
    }

    static void usersPage(HttpExchange e, AuthSession session, int status, String error) throws IOException, SQLException {
        StringBuilder content = new StringBuilder(message(e, error));
        content.append("<section class='account-panel'><h2>新規ユーザー登録</h2><p>一般ユーザーとして登録します。初回ログイン時にパスワード変更が必要です。</p><form class='account-form' method='post' action='/admin/users/create'>")
            .append(csrf(session)).append(input("username", "ユーザーID", "text", "autocomplete='off' maxlength='100' pattern='[A-Za-z0-9_.\\-]+'"))
            .append(input("displayName", "表示名", "text", "maxlength='100'"))
            .append(passwordInput("password", "初期パスワード（12〜128文字）"))
            .append("<button>ユーザーを登録</button></form></section><section class='account-panel'><h2>ユーザー一覧</h2><p>無効化してもWishデータは保持されます。無効化・パスワード再設定時は、対象ユーザーのログイン状態を解除します。</p><div class='account-table-wrap'><table class='account-table'><caption>登録済みアカウント</caption><thead><tr><th scope='col'>ユーザーID / 表示名</th><th scope='col'>権限</th><th scope='col'>状態</th><th scope='col'>操作</th></tr></thead><tbody>");
        try (Statement s = App.connection.createStatement(); ResultSet r = s.executeQuery("SELECT id, username, display_name, role, is_active, must_change_password FROM users ORDER BY id")) {
            while (r.next()) {
                int id = r.getInt("id"); boolean active = r.getInt("is_active") == 1;
                String username = App.escapeHtml(r.getString("username"));
                String hidden = csrf(session) + "<input type='hidden' name='id' value='" + id + "'>";
                content.append("<tr><th scope='row'>").append(username).append("<br><span class='account-display-name'>").append(App.escapeHtml(r.getString("display_name"))).append("</span></th><td>")
                    .append("admin".equals(r.getString("role")) ? "管理者" : "一般ユーザー").append("</td><td>").append(active ? "有効" : "無効")
                    .append(r.getInt("must_change_password") == 1 ? "<br>パスワード変更待ち" : "")
                    .append("</td><td><form method='post' action='/admin/users/status'>").append(hidden)
                    .append("<input type='hidden' name='active' value='").append(active ? "0" : "1").append("'><button>").append(active ? "無効にする" : "有効にする")
                    .append("</button></form><details><summary>パスワード再設定</summary><form class='account-form' method='post' action='/admin/users/reset-password'>").append(hidden)
                    .append(passwordInput("password", username + " の仮パスワード"))
                    .append("<button>再設定してログイン状態を解除</button></form></details></td></tr>");
            }
        }
        content.append("</tbody></table></div></section>");
        App.sendHtmlStatus(e, status, page("ユーザー管理", session, true, content.toString()));
    }
}
