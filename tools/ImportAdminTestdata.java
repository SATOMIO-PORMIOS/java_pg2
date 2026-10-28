import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** One-time, repeatable import of the supplied administrator CSV. */
public class ImportAdminTestdata {
    private static final List<String> HEADER = List.of("login_id", "user_id", "title", "done", "due_date", "budget", "completed_date", "completed_amount", "category_id", "category_name", "image_path", "location_name", "address", "latitude", "longitude", "memo", "priority");
    private static final String INSERT = "INSERT INTO todos(title,done,due_date,budget,completed_date,completed_amount,category_id,image_path,user_id,location_name,address,latitude,longitude,memo,priority) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    record Row(String title, int done, String due, Long budget, String completed, Long amount, String categoryName,
               String location, String address, Double latitude, Double longitude, String memo, int priority) { }
    record Added(long id, int userId, String title) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4 || !List.of("dry-run", "import").contains(args[0]) || (args[0].equals("import") != (args.length == 4))) {
            throw new IllegalArgumentException("Usage: ImportAdminTestdata dry-run DB CSV | import DB CSV MANIFEST");
        }
        Path db = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(db)) throw new IllegalArgumentException("Database does not exist");
        List<Row> rows = readRows(Path.of(args[2]));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=10000");
            }
            ensureColumns(connection);
            int adminId = adminId(connection);
            Map<String,Integer> categoryIds = categories(connection);
            for (Row row : rows) if (!categoryIds.containsKey(row.categoryName())) throw new IllegalArgumentException("Missing category name for " + row.title());
            int existing = 0;
            for (Row row : rows) if (exists(connection, adminId, row.title())) existing++;
            System.out.println("DRY_RUN db=" + db + " admin_id=" + adminId + " csv=50 existing_matches=" + existing + " to_insert=" + (50 - existing));
            if (args[0].equals("dry-run")) return;
            if (existing == 50) { System.out.println("IMPORT_OK inserted=0 skipped=50 manifest=unchanged"); return; }
            Path manifest = Path.of(args[3]).toAbsolutePath().normalize();
            if (Files.exists(manifest)) throw new IllegalArgumentException("Manifest already exists");
            int beforeTotal = count(connection, "SELECT COUNT(*) FROM todos");
            int beforeAdmin = count(connection, "SELECT COUNT(*) FROM todos WHERE user_id=" + adminId);
            String beforeDigest = digestExisting(connection, beforeTotal);
            Map<Integer,Integer> otherCounts = ownerCounts(connection, adminId);
            List<Added> added = new ArrayList<>();
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                try (PreparedStatement insert = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                    for (Row row : rows) {
                        if (exists(connection, adminId, row.title())) continue;
                        bind(insert, row, adminId, categoryIds.get(row.categoryName()));
                        if (insert.executeUpdate() != 1) throw new SQLException("Insert failed");
                        try (ResultSet key = insert.getGeneratedKeys()) {
                            if (!key.next()) throw new SQLException("Generated id missing");
                            added.add(new Added(key.getLong(1), adminId, row.title()));
                        }
                    }
                }
                if (count(connection, "SELECT COUNT(*) FROM todos") != beforeTotal + added.size()
                    || count(connection, "SELECT COUNT(*) FROM todos WHERE user_id=" + adminId) != beforeAdmin + added.size()
                    || !beforeDigest.equals(digestExisting(connection, beforeTotal))
                    || !otherCounts.equals(ownerCounts(connection, adminId))) throw new SQLException("Pre-existing Wishes changed");
                verifyAdded(connection, added, rows, categoryIds);
                if (count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) throw new SQLException("Foreign key check failed");
                writeManifest(manifest, added);
                connection.commit(); committed = true;
            } finally {
                if (!committed) { connection.rollback(); Files.deleteIfExists(manifest); }
                connection.setAutoCommit(true);
            }
            System.out.println("IMPORT_OK inserted=" + added.size() + " skipped=" + (50 - added.size()) + " total_before=" + beforeTotal + " total_after=" + (beforeTotal + added.size()) + " admin_before=" + beforeAdmin + " admin_after=" + (beforeAdmin + added.size()) + " manifest=" + manifest);
        }
    }

    private static List<Row> readRows(Path csv) throws Exception {
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = parseCsv(text);
        if (records.isEmpty() || !records.get(0).equals(HEADER)) throw new IllegalArgumentException("Unexpected CSV header");
        List<Row> rows = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> v = records.get(i);
            if (v.size() != HEADER.size() || !v.get(0).equals("admin") || !v.get(10).isEmpty()) throw new IllegalArgumentException("Invalid row " + (i + 1));
            String title = v.get(2);
            if (title.isBlank() || title.length() > 500 || !titles.add(title)) throw new IllegalArgumentException("Duplicate or invalid title");
            int done = Integer.parseInt(v.get(3));
            if (done != 0 && done != 1) throw new IllegalArgumentException("Invalid done");
            String due = date(v.get(4)), completed = date(v.get(6));
            Long budget = amount(v.get(5)), amount = amount(v.get(7));
            if ((completed != null) != (done == 1) || done == 0 && amount != null) throw new IllegalArgumentException("Completion mismatch for " + title);
            int priority = Integer.parseInt(v.get(16));
            if (priority < 1 || priority > 5) throw new IllegalArgumentException("Invalid priority");
            String location = nullable(v.get(11)), address = nullable(v.get(12)), memo = nullable(v.get(15));
            if (location != null && location.length() > 120 || address != null && address.length() > 500 || memo != null && memo.length() > 5000) throw new IllegalArgumentException("Text too long");
            // CSV user_id and category_id are reference values; live IDs are resolved from the DB.
            Integer.parseInt(v.get(1)); Integer.parseInt(v.get(8));
            rows.add(new Row(title, done, due, budget, completed, amount, v.get(9), location, address,
                coordinate(v.get(13), -90, 90), coordinate(v.get(14), -180, 180), memo, priority));
        }
        if (rows.size() != 50) throw new IllegalArgumentException("Expected exactly 50 rows");
        return rows;
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else field.append(ch);
            } else if (ch == '"' && field.length() == 0) quoted = true;
            else if (ch == ',') { row.add(field.toString()); field.setLength(0); }
            else if (ch == '\n') {
                row.add(field.toString()); field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) records.add(row);
                row = new ArrayList<>();
            } else if (ch != '\r') field.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); records.add(row); }
        return records;
    }

    private static String nullable(String text) { return text.isEmpty() ? null : text; }
    private static Long amount(String text) { if (text.isEmpty()) return null; long amount = Long.parseLong(text); if (amount < 0) throw new IllegalArgumentException("Negative amount"); return amount; }
    private static String date(String text) { if (text.isEmpty()) return null; if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid date"); LocalDate.parse(text); return text; }
    private static Double coordinate(String text, double min, double max) { if (text.isEmpty()) return null; double value = Double.parseDouble(text); if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid coordinate"); return value; }
    private static void ensureColumns(Connection c) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info(todos)")) { while(r.next()) names.add(r.getString("name")); }
        for(String name:List.of("id","title","done","due_date","budget","completed_date","completed_amount","category_id","image_path","user_id","location_name","address","latitude","longitude","memo","priority")) if(!names.contains(name)) throw new SQLException("Missing column "+name);
    }
    private static int adminId(Connection c) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT id,role FROM users WHERE username='admin'");ResultSet r=s.executeQuery()) {
            if(!r.next() || !"admin".equals(r.getString("role"))) throw new SQLException("Administrator account missing"); return r.getInt("id");
        }
    }
    private static Map<String,Integer> categories(Connection c) throws SQLException {
        Map<String,Integer> ids=new HashMap<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,name FROM categories")){while(r.next())ids.put(r.getString("name"),r.getInt("id"));}
        return ids;
    }
    private static boolean exists(Connection c,int owner,String title) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM todos WHERE user_id=? AND title=? LIMIT 1")){s.setInt(1,owner);s.setString(2,title);try(ResultSet r=s.executeQuery()){return r.next();}}
    }
    private static void bind(PreparedStatement s,Row row,int owner,int category) throws SQLException {
        s.setString(1,row.title());s.setInt(2,row.done());text(s,3,row.due());number(s,4,row.budget());text(s,5,row.completed());number(s,6,row.amount());s.setInt(7,category);s.setNull(8,Types.VARCHAR);s.setInt(9,owner);
        text(s,10,row.location());text(s,11,row.address());real(s,12,row.latitude());real(s,13,row.longitude());text(s,14,row.memo());s.setInt(15,row.priority());
    }
    private static void text(PreparedStatement s,int index,String value)throws SQLException{if(value==null)s.setNull(index,Types.VARCHAR);else s.setString(index,value);}
    private static void number(PreparedStatement s,int index,Long value)throws SQLException{if(value==null)s.setNull(index,Types.INTEGER);else s.setLong(index,value);}
    private static void real(PreparedStatement s,int index,Double value)throws SQLException{if(value==null)s.setNull(index,Types.REAL);else s.setDouble(index,value);}
    private static int count(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}}
    private static Map<Integer,Integer> ownerCounts(Connection c,int admin) throws SQLException {
        Map<Integer,Integer> counts=new HashMap<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT user_id,COUNT(*) FROM todos WHERE user_id<>"+admin+" GROUP BY user_id")){while(r.next())counts.put(r.getInt(1),r.getInt(2));}
        return counts;
    }
    private static String digestExisting(Connection c,int rows)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(PreparedStatement s=c.prepareStatement("SELECT * FROM todos ORDER BY id LIMIT ?")){s.setInt(1,rows);try(ResultSet r=s.executeQuery()){var m=r.getMetaData();while(r.next())for(int i=1;i<=m.getColumnCount();i++){String v=r.getString(i);digest.update((v==null?"-1:":v.length()+":"+v).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}}}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void verifyAdded(Connection c,List<Added> added,List<Row> rows,Map<String,Integer> categoryIds)throws SQLException{
        Map<String,Row> expected=new HashMap<>();for(Row row:rows)expected.put(row.title(),row);
        try(PreparedStatement s=c.prepareStatement("SELECT title,done,due_date,budget,completed_date,completed_amount,category_id,image_path,user_id,location_name,address,latitude,longitude,memo,priority FROM todos WHERE id=?")){
            for(Added entry:added){Row row=expected.get(entry.title());s.setLong(1,entry.id());try(ResultSet r=s.executeQuery()){
                if(!r.next() || !row.title().equals(r.getString("title")) || row.done()!=r.getInt("done") || !Objects.equals(row.due(),r.getString("due_date"))
                    || !Objects.equals(row.budget(),nullableLong(r,"budget")) || !Objects.equals(row.completed(),r.getString("completed_date")) || !Objects.equals(row.amount(),nullableLong(r,"completed_amount"))
                    || categoryIds.get(row.categoryName())!=r.getInt("category_id") || r.getString("image_path")!=null || entry.userId()!=r.getInt("user_id")
                    || !Objects.equals(row.location(),r.getString("location_name")) || !Objects.equals(row.address(),r.getString("address"))
                    || !Objects.equals(row.latitude(),nullableDouble(r,"latitude")) || !Objects.equals(row.longitude(),nullableDouble(r,"longitude"))
                    || !Objects.equals(row.memo(),r.getString("memo")) || row.priority()!=r.getInt("priority"))throw new SQLException("Inserted data mismatch: "+entry.title());
            }}
        }
    }
    private static Long nullableLong(ResultSet r,String column)throws SQLException{long v=r.getLong(column);return r.wasNull()?null:v;}
    private static Double nullableDouble(ResultSet r,String column)throws SQLException{double v=r.getDouble(column);return r.wasNull()?null:v;}
    private static void writeManifest(Path path,List<Added> added)throws Exception{
        StringBuilder text=new StringBuilder("id,user_id,title\n");
        for(Added row:added)text.append(row.id()).append(',').append(row.userId()).append(',').append('"').append(row.title().replace("\"","\"\"")).append('"').append('\n');
        Files.writeString(path,text.toString(),StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
