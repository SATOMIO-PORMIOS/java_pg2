import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Optional Wish fields and their database migration/validation. */
final class WishDetails {
    final String locationName;
    final String address;
    final Double latitude;
    final Double longitude;
    final String memo;
    final int priority;

    private WishDetails(String locationName, String address, Double latitude, Double longitude, String memo, int priority) {
        this.locationName = locationName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.memo = memo;
        this.priority = priority;
    }

    static void ensureSchema(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(todos)")) {
            while (result.next()) columns.add(result.getString("name"));
        }
        // Each ALTER is guarded, so startup can safely resume after a partial migration.
        Map<String, String> additions = Map.of(
            "location_name", "TEXT",
            "address", "TEXT",
            "latitude", "REAL CHECK(latitude BETWEEN -90 AND 90)",
            "longitude", "REAL CHECK(longitude BETWEEN -180 AND 180)",
            "memo", "TEXT",
            "priority", "INTEGER NOT NULL DEFAULT 3 CHECK(priority BETWEEN 1 AND 5)"
        );
        for (String column : new String[] {"location_name", "address", "latitude", "longitude", "memo", "priority"}) {
            if (!columns.contains(column)) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE todos ADD COLUMN " + column + " " + additions.get(column));
                }
            }
        }
    }

    static WishDetails parse(Map<String, byte[]> parts) {
        String locationName = optionalText(parts, "locationName", 120);
        String address = optionalText(parts, "address", 500);
        String memo = optionalText(parts, "memo", 5000);
        Double latitude = coordinate(parts, "latitude", -90, 90);
        Double longitude = coordinate(parts, "longitude", -180, 180);
        String rawPriority = App.partText(parts, "priority");
        int priority = rawPriority == null || rawPriority.isBlank() ? 3 : rawPriority.matches("[1-5]") ? Integer.parseInt(rawPriority) : -1;
        if (priority == -1) throw new IllegalArgumentException("優先度は1〜5で入力してください。");
        return new WishDetails(locationName, address, latitude, longitude, memo, priority);
    }

    private static String optionalText(Map<String, byte[]> parts, String name, int maxLength) {
        String value = App.partText(parts, name);
        if (value == null || value.isBlank()) return null;
        if (value.length() > maxLength || value.indexOf('\0') >= 0) throw new IllegalArgumentException(name + " is too long or invalid");
        return value;
    }

    private static Double coordinate(Map<String, byte[]> parts, String name, double min, double max) {
        String value = App.partText(parts, name);
        if (value == null || value.isBlank()) return null;
        double number;
        try { number = Double.parseDouble(value.trim()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be a number"); }
        if (!Double.isFinite(number) || number < min || number > max) throw new IllegalArgumentException(name + " is outside the allowed range");
        return number;
    }

    void bind(PreparedStatement statement, int first) throws SQLException {
        nullableText(statement, first, locationName);
        nullableText(statement, first + 1, address);
        nullableReal(statement, first + 2, latitude);
        nullableReal(statement, first + 3, longitude);
        nullableText(statement, first + 4, memo);
        statement.setInt(first + 5, priority);
    }

    private static void nullableText(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static void nullableReal(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) statement.setNull(index, Types.REAL); else statement.setDouble(index, value);
    }
}
