import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Checks the rendered admin summary using an authenticated session in a copied DB. */
public class AdminImportedListViewTest {
    public static void main(String[] args) throws Exception {
        try {
            App.main(new String[0]);
            int adminId;
            try (Statement s=App.connection.createStatement(); ResultSet r=s.executeQuery("SELECT id FROM users WHERE username='admin' AND role='admin'")) {
                if(!r.next()) throw new AssertionError("Admin missing"); adminId=r.getInt(1);
            }
            try (Statement s=App.connection.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(*),SUM(done),SUM(CASE WHEN done=0 THEN budget ELSE 0 END) FROM todos WHERE user_id="+adminId)) {
                r.next(); if(r.getInt(1)!=64 || r.getInt(2)!=18 || r.getLong(3)!=4_398_234L) throw new AssertionError("Unexpected admin database summary");
            }
            AuthSession session=App.createSession(adminId,"admin",App.SESSION_IDLE_TIMEOUT_MS);
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+App.SERVER_PORT+"/"))
                .header("Cookie",App.SESSION_COOKIE+"="+session.rawToken).GET().build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200) throw new AssertionError("HTTP status "+response.statusCode());
            String html=response.body();
            if(!html.contains(">28%</span>") || !html.contains("4,398,234円")) throw new AssertionError("Rendered summary does not match database");
            if(html.split("data-edit-id=",-1).length-1!=64) throw new AssertionError("Admin card count mismatch");
            try(Statement s=App.connection.createStatement(); ResultSet r=s.executeQuery("SELECT title FROM todos WHERE user_id<>"+adminId+" ORDER BY id LIMIT 1")){
                if(r.next() && html.contains(App.escapeHtml(r.getString(1)))) throw new AssertionError("Other user's Wish visible to admin");
            }
            System.out.println("VIEW_OK admin_cards=64 completed=18 progress=28% pending_budget=4,398,234 other_users_hidden=true");
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
