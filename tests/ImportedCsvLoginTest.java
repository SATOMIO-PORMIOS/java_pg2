import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/** Uses temporary passwords in a copied database to check imported lists via real login. */
public class ImportedCsvLoginTest {
    public static void main(String[] args) throws Exception {
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:todos.db");
                 PreparedStatement update = connection.prepareStatement("UPDATE users SET password_hash=?, must_change_password=0 WHERE username=?")) {
                for (String login : List.of("6514", "9514", "3547")) {
                    byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
                    update.setString(1, App.hashPassword(WishOwnershipTest.PASSWORD, salt));
                    update.setString(2, login);
                    if (update.executeUpdate() != 1) throw new AssertionError("Missing test user " + login);
                }
            }
            App.main(new String[0]);
            Map<String,List<String>> titles = new LinkedHashMap<>();
            try (Statement statement = App.connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT u.username,t.title FROM todos t JOIN users u ON u.id=t.user_id WHERE u.username IN ('6514','9514','3547') ORDER BY t.id")) {
                while (rows.next()) titles.computeIfAbsent(rows.getString(1), key -> new ArrayList<>()).add(rows.getString(2));
            }
            int checks = 0;
            for (String login : List.of("6514", "9514", "3547")) {
                WishOwnershipTest.Browser browser = new WishOwnershipTest.Browser();
                if (!WishOwnershipTest.redirect(browser.login(login), "/")) throw new AssertionError("Login failed for " + login);
                var response = browser.get("/");
                if (response.statusCode() != 200) throw new AssertionError("List failed for " + login);
                String html = new String(response.body(), StandardCharsets.UTF_8);
                if (titles.getOrDefault(login, List.of()).size() != 12) throw new AssertionError("Expected 12 imported Wishes for " + login);
                for (String owner : titles.keySet()) for (String title : titles.get(owner)) {
                    boolean present = html.contains(App.escapeHtml(title));
                    if (present != owner.equals(login)) throw new AssertionError("List ownership error: viewer=" + login + " owner=" + owner + " title=" + title);
                    checks++;
                }
                if (html.split("data-edit-id=", -1).length - 1 != 12) throw new AssertionError("Expected 12 cards for " + login);
                System.out.println("LOGIN_LIST_OK login=" + login + " own=12 foreign=0");
            }
            System.out.println("PASS: " + checks + " imported Wish visibility checks");
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
