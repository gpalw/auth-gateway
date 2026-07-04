import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

public final class H2ToPostgresCopy {

    private record TableCopy(String table, List<String> columns) {
    }

    private static final List<TableCopy> TABLES = List.of(
            new TableCopy("app_users", List.of(
                    "id", "avatar_url", "created_at", "display_name", "email", "last_login_at")),
            new TableCopy("platform_registrations", List.of(
                    "id", "client_id", "client_secret", "name", "description", "home_url",
                    "enabled", "created_at", "updated_at")),
            new TableCopy("external_accounts", List.of(
                    "id", "email", "last_login_at", "linked_at", "provider", "provider_subject", "user_id")),
            new TableCopy("platform_redirect_uris", List.of("platform_id", "redirect_uri")),
            new TableCopy("platform_logout_redirect_uris", List.of("platform_id", "redirect_uri")));

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println(
                    "Usage: H2ToPostgresCopy <h2-jdbc-url> <h2-user> <h2-password> <postgres-jdbc-url> <postgres-user> <postgres-password>");
            System.exit(2);
        }

        try (Connection h2 = DriverManager.getConnection(args[0], args[1], args[2]);
                Connection postgres = DriverManager.getConnection(args[3], args[4], args[5])) {
            postgres.setAutoCommit(false);
            try {
                truncate(postgres);
                for (TableCopy table : TABLES) {
                    int copied = copyTable(h2, postgres, table);
                    System.out.printf("copied %s rows=%d%n", table.table(), copied);
                }
                postgres.commit();
            } catch (Exception ex) {
                postgres.rollback();
                throw ex;
            }
        }
    }

    private static void truncate(Connection postgres) throws Exception {
        try (Statement statement = postgres.createStatement()) {
            statement.execute("""
                    truncate table
                      platform_logout_redirect_uris,
                      platform_redirect_uris,
                      external_accounts,
                      platform_registrations,
                      app_users
                    """);
        }
    }

    private static int copyTable(Connection h2, Connection postgres, TableCopy table) throws Exception {
        String columnList = String.join(", ", table.columns());
        String placeholders = String.join(", ", table.columns().stream().map(ignored -> "?").toList());
        String selectSql = "select " + columnList + " from " + table.table();
        String insertSql = "insert into " + table.table() + " (" + columnList + ") values (" + placeholders + ")";
        int copied = 0;

        try (Statement select = h2.createStatement();
                ResultSet rows = select.executeQuery(selectSql);
                PreparedStatement insert = postgres.prepareStatement(insertSql)) {
            int columnCount = table.columns().size();
            while (rows.next()) {
                for (int index = 1; index <= columnCount; index++) {
                    insert.setObject(index, rows.getObject(index));
                }
                insert.addBatch();
                copied++;
            }
            int[] results = insert.executeBatch();
            int affected = Arrays.stream(results)
                    .filter(result -> result > 0)
                    .sum();
            if (affected > 0 && affected != copied) {
                throw new IllegalStateException(
                        "Unexpected affected row count for " + table.table() + ": " + affected + " vs " + copied);
            }
        }
        return copied;
    }
}
