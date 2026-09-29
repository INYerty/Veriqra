package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.config.EnvironmentVariables;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual 29-table Collaboration baseline -> additive R5.2-B migration on an isolated schema. */
@Tag("mysql")
class TaskCreditMigrationIntegrationTest {
    @Test void migrationPreservesExistingUsersAccountsLedgerProjectTeamTaskAndEvent() throws Exception {
        String selected = EnvironmentVariables.get("TEST_CONFIG");
        if (selected == null) selected = System.getProperty("veriqra.test.config", "config/database-test.local.properties");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(selected))) { properties.load(reader); }
        String url = properties.getProperty("jdbcUrl");
        String schemaName = MysqlFixture.requireTestUrl(url);
        if (!url.startsWith("jdbc:mysql://127.0.0.1:13307/") || "root".equalsIgnoreCase(properties.getProperty("username")))
            throw new IllegalStateException("Migration test requires dedicated 13307 non-root test account");
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getProperty("username"));
        credentials.setProperty("password", properties.getProperty("password"));
        String full = Files.readString(Path.of("database/schema.sql"));
        String marker = "-- R5.2-B task credit, handoff and contribution structures.";
        int split = full.indexOf(marker);
        if (split < 0) throw new IllegalStateException("R5.2-B schema marker is missing");
        String baseline = full.substring(0, split);
        String migration = Files.readString(Path.of("database/migrations/20260927-task-credit.sql"));
        if (tableCount(baseline) != 29 || tableCount(migration) != 3) throw new IllegalStateException("Unexpected migration scope");
        List<String> owned = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            if (!schemaName.equals(connection.getCatalog())) throw new IllegalStateException("Wrong test catalog");
            try (var statement = connection.prepareStatement("SELECT VERSION(),@@port,GET_LOCK(?,0)")) {
                statement.setString(1, "veriqra-jdbc-tests:" + UUID.nameUUIDFromBytes(schemaName.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (!"8.0.46".equals(rows.getString(1)) || rows.getInt(2) != 13307 || rows.getInt(3) != 1)
                        throw new IllegalStateException("Wrong MySQL version, port or concurrent owner");
                }
            }
            MysqlFixture.requireEmptySchema(connection, schemaName);
            try {
                MysqlFixture.installSchema(connection, baseline, owned);
                assertEquals(29, tableCount(connection));
                seedSentinel(connection);
                String before = sentinel(connection);
                MysqlFixture.installSchema(connection, migration, owned);
                assertEquals(32, tableCount(connection));
                assertEquals(before, sentinel(connection), "The existing data and ledger must be untouched");
                assertEquals(0, scalar(connection, "SELECT reward_credit FROM work_tasks WHERE id=9001"));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM credit_transfers"));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM task_handoff_offers"));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM contribution_events"));
                assertThrows(SQLException.class, () -> statement(connection, "UPDATE work_tasks SET reward_credit=-1 WHERE id=9001"));
            } finally {
                owned.remove("credit_transactions");
                owned.add("credit_transactions");
                // All names came from the checked-in schema/migration. The fixture verifies catalog ownership.
                MysqlFixture.cleanup(null, connection, schemaName, owned);
            }
        }
        // cleanup() closed the owner; read-only fresh connection verifies the empty schema.
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            assertEquals(0, tableCount(connection));
        }
    }

    private static int tableCount(String sql) {
        Matcher matcher = Pattern.compile("CREATE TABLE `([a-z_]+)`").matcher(sql);
        int count = 0; while (matcher.find()) count++; return count;
    }
    private static int tableCount(Connection c) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()");
    }
    private static int scalar(Connection c, String sql) throws SQLException {
        try (var statement = c.prepareStatement(sql); var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
    }
    private static void statement(Connection c, String sql) throws SQLException {
        try (var statement = c.prepareStatement(sql)) { statement.executeUpdate(); }
    }
    private static void seedSentinel(Connection c) throws SQLException {
        statement(c, "INSERT INTO users(id,username,display_name,password_hash,system_role,status) VALUES(9001,'migration_user','Migration User','fixture-hash','USER','ACTIVE')");
        statement(c, "INSERT INTO projects(id,project_key,name,created_by) VALUES(9001,'MIGRATION','Migration project',9001)");
        statement(c, "INSERT INTO project_members(project_id,user_id,project_role) VALUES(9001,9001,'TESTER')");
        statement(c, "INSERT INTO credit_accounts(user_id,balance) VALUES(9001,7)");
        statement(c, "INSERT INTO credit_transactions(user_id,amount,type,actor_user_id,reason) VALUES(9001,7,'GRANT',9001,'existing credit')");
        statement(c, "INSERT INTO project_teams(id,project_id,name,lead_user_id,created_by) VALUES(9001,9001,'Migration team',9001,9001)");
        statement(c, "INSERT INTO team_members(team_id,project_id,user_id) VALUES(9001,9001,9001)");
        statement(c, "INSERT INTO work_tasks(id,project_id,team_id,title,assignee_user_id,created_by) VALUES(9001,9001,9001,'Existing task',9001,9001)");
        statement(c, "INSERT INTO work_task_events(task_id,actor_user_id,event_type,to_status,to_assignee_user_id,note) VALUES(9001,9001,'CREATED','OPEN',9001,'existing event')");
    }
    private static String sentinel(Connection c) throws SQLException {
        String[] queries = { "SELECT username,display_name,status FROM users WHERE id=9001",
                "SELECT project_key,name,status FROM projects WHERE id=9001",
                "SELECT balance,lock_version FROM credit_accounts WHERE user_id=9001",
                "SELECT user_id,amount,type,actor_user_id,reason FROM credit_transactions WHERE user_id=9001",
                "SELECT name,lead_user_id,status FROM project_teams WHERE id=9001",
                "SELECT title,assignee_user_id,status,lock_version FROM work_tasks WHERE id=9001",
                "SELECT event_type,to_status,to_assignee_user_id,note FROM work_task_events WHERE task_id=9001" };
        StringBuilder values = new StringBuilder();
        for (String query : queries) try (var statement = c.prepareStatement(query); var rows = statement.executeQuery()) {
            assertTrue(rows.next(), query);
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) values.append(rows.getString(i)).append('|');
            assertFalse(rows.next(), query);
            values.append('\n');
        }
        return values.toString();
    }
}
