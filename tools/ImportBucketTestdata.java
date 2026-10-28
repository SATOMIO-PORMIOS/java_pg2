import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Imports the supplied 36-row CSV without touching pre-existing Wishes. */
public class ImportBucketTestdata {
    private static final List<String> HEADER = List.of("login_id", "user_id", "title", "done", "due_date", "budget", "completed_date", "completed_amount", "category_id", "category_name", "image_path", "location_name", "address", "latitude", "longitude", "memo", "priority");
    private static final String INSERT = "INSERT INTO todos(title,done,due_date,budget,completed_date,completed_amount,category_id,image_path,user_id,location_name,address,latitude,longitude,memo,priority) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    record Row(String login, int csvUserId, String title, int done, String due, Long budget, String completed, Long amount,
               int categoryId, String categoryName, String location, String address, Double latitude, Double longitude, String memo, int priority) { }
    record Imported(long id, int userId, String login, String title) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4 || !List.of("dry-run", "import", "verify").contains(args[0]) || (args[0].equals("import") != (args.length == 4))) {
            throw new IllegalArgumentException("Usage: ImportBucketTestdata dry-run|verify DB CSV ; import DB CSV MANIFEST");
        }
        String mode = args[0];
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)) throw new IOException("Database does not exist: " + database);
        List<Row> rows = readCsv(Path.of(args[2]));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 10000");
            }
            validateSchema(connection);
            Map<String,Integer> userIds = users(connection);
            Map<Integer,String> categories = categories(connection);
            validateReferences(rows, userIds, categories);
            if (mode.equals("verify")) {
                verifyPresent(connection, rows, userIds);
                System.out.println("VERIFY_OK rows=" + rows.size() + " database=" + database);
                return;
            }
            int alreadyPresent = 0;
            Map<String,Integer> planned = new LinkedHashMap<>();
            for (Row row : rows) {
                int id = userIds.get(row.login());
                if (exists(connection, id, row.title())) alreadyPresent++;
                else planned.merge(row.login(), 1, Integer::sum);
            }
            System.out.println("DRY_RUN database=" + database + " csv_rows=" + rows.size() + " existing_matches=" + alreadyPresent + " to_insert=" + (rows.size() - alreadyPresent));
            for (String login : List.of("6514", "9514", "3547")) {
                System.out.println("  login=" + login + " db_user_id=" + userIds.get(login) + " planned=" + planned.getOrDefault(login, 0));
            }
            if (mode.equals("dry-run")) return;
            if (alreadyPresent == rows.size()) {
                verifyPresent(connection, rows, userIds);
                System.out.println("IMPORT_OK inserted=0 skipped=" + alreadyPresent + " manifest=unchanged");
                return;
            }
            Path manifest = Path.of(args[3]).toAbsolutePath().normalize();
            if (Files.exists(manifest)) throw new IOException("Manifest already exists: " + manifest);
            List<Imported> inserted = new ArrayList<>();
            int beforeCount = count(connection, "SELECT COUNT(*) FROM todos");
            String beforeRows = existingDigest(connection);
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                try (PreparedStatement insert = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                    for (Row row : rows) {
                        int userId = userIds.get(row.login());
                        if (exists(connection, userId, row.title())) continue;
                        bind(insert, row, userId);
                        if (insert.executeUpdate() != 1) throw new SQLException("Insert failed");
                        try (ResultSet key = insert.getGeneratedKeys()) {
                            if (!key.next()) throw new SQLException("Generated Wish ID missing");
                            inserted.add(new Imported(key.getLong(1), userId, row.login(), row.title()));
                        }
                    }
                }
                if (count(connection, "SELECT COUNT(*) FROM todos") != beforeCount + inserted.size()) throw new SQLException("Wish count changed unexpectedly");
                if (!beforeRows.equals(existingDigest(connection, beforeCount))) throw new SQLException("Existing Wishes changed");
                verifyPresent(connection, rows, userIds);
                verifyInserted(connection, rows, inserted);
                writeManifest(manifest, inserted, database, args[2]);
                connection.commit();
                committed = true;
            } finally {
                if (!committed) {
                    connection.rollback();
                    Files.deleteIfExists(manifest);
                }
                connection.setAutoCommit(true);
            }
            System.out.println("IMPORT_OK inserted=" + inserted.size() + " skipped=" + (rows.size() - inserted.size()) + " before=" + beforeCount + " after=" + (beforeCount + inserted.size()) + " manifest=" + manifest);
        }
    }

    private static List<Row> readCsv(Path path) throws Exception {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = parseCsv(text);
        if (records.isEmpty() || !records.get(0).equals(HEADER)) throw new IllegalArgumentException("CSV columns do not match the expected format");
        List<Row> rows = new ArrayList<>();
        Map<String,Integer> perUser = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> v = records.get(i);
            if (v.size() != HEADER.size()) throw new IllegalArgumentException("CSV column count on row " + (i + 1));
            String login = v.get(0), title = v.get(2);
            if (!List.of("6514", "9514", "3547").contains(login) || title.isBlank() || title.length() > 500) throw new IllegalArgumentException("Invalid login or title on row " + (i + 1));
            if (!keys.add(login + "\0" + title)) throw new IllegalArgumentException("Duplicate CSV title for " + login);
            int done = integer(v.get(3));
            if (done != 0 && done != 1) throw new IllegalArgumentException("Invalid done value");
            String due = date(v.get(4)), completed = date(v.get(6));
            Long budget = amount(v.get(5)), completedAmount = amount(v.get(7));
            if ((done == 1) != (completed != null) || (done == 0 && completedAmount != null)) throw new IllegalArgumentException("Completion fields are inconsistent on row " + (i + 1));
            if (!v.get(10).isBlank()) throw new IllegalArgumentException("CSV image_path must be empty");
            int priority = integer(v.get(16));
            if (priority < 1 || priority > 5) throw new IllegalArgumentException("Priority outside 1..5");
            String location = nullable(v.get(11)), address = nullable(v.get(12)), memo = nullable(v.get(15));
            if (location != null && location.length() > 120 || address != null && address.length() > 500 || memo != null && memo.length() > 5000) throw new IllegalArgumentException("Text too long");
            Row row = new Row(login, integer(v.get(1)), title, done, due, budget, completed, completedAmount,
                integer(v.get(8)), v.get(9), location, address, coordinate(v.get(13), -90, 90), coordinate(v.get(14), -180, 180), memo, priority);
            rows.add(row);
            perUser.merge(login, 1, Integer::sum);
        }
        if (rows.size() != 36 || perUser.size() != 3 || perUser.values().stream().anyMatch(count -> count != 12)) throw new IllegalArgumentException("Expected exactly 12 rows for each of three users");
        return rows;
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else field.append(ch);
            } else if (ch == '"' && field.length() == 0) quoted = true;
            else if (ch == ',') { record.add(field.toString()); field.setLength(0); }
            else if (ch == '\n') {
                record.add(field.toString()); field.setLength(0);
                if (!record.isEmpty() && !(record.size() == 1 && record.get(0).isEmpty())) records.add(record);
                record = new ArrayList<>();
            } else if (ch != '\r') field.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
        if (field.length() > 0 || !record.isEmpty()) { record.add(field.toString()); records.add(record); }
        return records;
    }

    private static String nullable(String value) { return value.isEmpty() ? null : value; }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static Long amount(String value) { if (value.isEmpty()) return null; long amount = Long.parseLong(value); if (amount < 0) throw new IllegalArgumentException("Negative amount"); return amount; }
    private static String date(String value) { if (value.isEmpty()) return null; if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid date format"); LocalDate.parse(value); return value; }
    private static Double coordinate(String value, double min, double max) {
        if (value.isEmpty()) return null;
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number) || number < min || number > max) throw new IllegalArgumentException("Coordinate out of range");
        return number;
    }

    private static void validateSchema(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(todos)")) {
            while (result.next()) columns.add(result.getString("name"));
        }
        for (String column : List.of("id","title","done","due_date","budget","completed_date","completed_amount","category_id","image_path","user_id","location_name","address","latitude","longitude","memo","priority"))
            if (!columns.contains(column)) throw new SQLException("Missing todos column: " + column);
    }

    private static Map<String,Integer> users(Connection connection) throws SQLException {
        Map<String,Integer> result = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT id,username FROM users")) {
            while (rows.next()) result.put(rows.getString("username"), rows.getInt("id"));
        }
        return result;
    }

    private static Map<Integer,String> categories(Connection connection) throws SQLException {
        Map<Integer,String> result = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT id,name FROM categories")) {
            while (rows.next()) result.put(rows.getInt("id"), rows.getString("name"));
        }
        return result;
    }

    private static void validateReferences(List<Row> rows, Map<String,Integer> users, Map<Integer,String> categories) {
        for (Row row : rows) {
            Integer actualId = users.get(row.login());
            if (actualId == null) throw new IllegalArgumentException("Missing login ID: " + row.login());
            if (actualId != row.csvUserId()) throw new IllegalArgumentException("CSV user_id differs from the current DB mapping for " + row.login());
            if (!row.categoryName().equals(categories.get(row.categoryId()))) throw new IllegalArgumentException("Category ID/name mismatch for " + row.title());
        }
    }

    private static boolean exists(Connection connection, int userId, String title) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM todos WHERE user_id = ? AND title = ? LIMIT 1")) {
            query.setInt(1, userId); query.setString(2, title);
            try (ResultSet result = query.executeQuery()) { return result.next(); }
        }
    }

    private static void bind(PreparedStatement insert, Row row, int userId) throws SQLException {
        insert.setString(1, row.title()); insert.setInt(2, row.done());
        text(insert, 3, row.due()); integer(insert, 4, row.budget());
        text(insert, 5, row.completed()); integer(insert, 6, row.amount());
        insert.setInt(7, row.categoryId()); insert.setNull(8, java.sql.Types.VARCHAR); insert.setInt(9, userId);
        text(insert, 10, row.location()); text(insert, 11, row.address());
        real(insert, 12, row.latitude()); real(insert, 13, row.longitude());
        text(insert, 14, row.memo()); insert.setInt(15, row.priority());
    }

    private static void text(PreparedStatement statement, int index, String value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.VARCHAR); else statement.setString(index, value); }
    private static void integer(PreparedStatement statement, int index, Long value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.INTEGER); else statement.setLong(index, value); }
    private static void real(PreparedStatement statement, int index, Double value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.REAL); else statement.setDouble(index, value); }
    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) { result.next(); return result.getInt(1); }
    }

    private static String existingDigest(Connection connection) throws Exception { return existingDigest(connection, Integer.MAX_VALUE); }
    private static String existingDigest(Connection connection, int maxRows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM todos ORDER BY id LIMIT ?")) {
            statement.setInt(1, maxRows);
            try (ResultSet rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                while (rows.next()) for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    String value = rows.getString(i);
                    digest.update((value == null ? "-1:" : value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte)0);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyPresent(Connection connection, List<Row> rows, Map<String,Integer> users) throws SQLException {
        Map<String,Integer> counts = new HashMap<>();
        for (Row row : rows) {
            try (PreparedStatement query = connection.prepareStatement("SELECT done,due_date,budget,completed_date,completed_amount,category_id,image_path,user_id,location_name,address,latitude,longitude,memo,priority FROM todos WHERE user_id=? AND title=? LIMIT 1")) {
                query.setInt(1, users.get(row.login())); query.setString(2, row.title());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("Missing imported Wish: " + row.title());
                    if (result.getInt("user_id") != users.get(row.login())) throw new SQLException("Owner mismatch");
                    counts.merge(row.login(), 1, Integer::sum);
                }
            }
        }
        for (String login : List.of("6514", "9514", "3547")) if (counts.getOrDefault(login, 0) != 12) throw new SQLException("Expected 12 CSV Wishes for " + login);
        if (count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) throw new SQLException("Foreign key violation");
        if (count(connection, "SELECT COUNT(*) FROM todos WHERE priority NOT BETWEEN 1 AND 5 OR latitude NOT BETWEEN -90 AND 90 OR longitude NOT BETWEEN -180 AND 180") != 0) throw new SQLException("Range violation");
    }

    private static void verifyInserted(Connection connection, List<Row> source, List<Imported> inserted) throws SQLException {
        Map<String,Row> expected = new HashMap<>();
        for (Row row : source) expected.put(row.login() + "\0" + row.title(), row);
        try (PreparedStatement query = connection.prepareStatement("SELECT title,done,due_date,budget,completed_date,completed_amount,category_id,image_path,user_id,location_name,address,latitude,longitude,memo,priority FROM todos WHERE id=?")) {
            for (Imported record : inserted) {
                Row row = expected.get(record.login() + "\0" + record.title());
                query.setLong(1, record.id());
                try (ResultSet actual = query.executeQuery()) {
                    if (!actual.next() || !record.title().equals(actual.getString("title")) || record.userId() != actual.getInt("user_id")
                            || row.done() != actual.getInt("done") || row.categoryId() != actual.getInt("category_id") || row.priority() != actual.getInt("priority")
                            || !java.util.Objects.equals(row.due(), actual.getString("due_date"))
                            || !java.util.Objects.equals(row.budget(), nullableLong(actual, "budget"))
                            || !java.util.Objects.equals(row.completed(), actual.getString("completed_date"))
                            || !java.util.Objects.equals(row.amount(), nullableLong(actual, "completed_amount"))
                            || actual.getString("image_path") != null
                            || !java.util.Objects.equals(row.location(), actual.getString("location_name"))
                            || !java.util.Objects.equals(row.address(), actual.getString("address"))
                            || !java.util.Objects.equals(row.latitude(), nullableDouble(actual, "latitude"))
                            || !java.util.Objects.equals(row.longitude(), nullableDouble(actual, "longitude"))
                            || !java.util.Objects.equals(row.memo(), actual.getString("memo"))) {
                        throw new SQLException("Imported row differs from CSV: " + record.title());
                    }
                }
            }
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static void writeManifest(Path path, List<Imported> rows, Path database, String csv) throws IOException {
        StringBuilder content = new StringBuilder("id,user_id,login_id,title\n");
        for (Imported row : rows) content.append(row.id()).append(',').append(row.userId()).append(',').append(row.login()).append(',').append('"').append(row.title().replace("\"", "\"\"")).append('"').append('\n');
        Files.writeString(path, content.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
