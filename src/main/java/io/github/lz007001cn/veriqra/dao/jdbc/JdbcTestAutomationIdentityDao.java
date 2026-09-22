package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.TestAutomationIdentityDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Non-owning connection-scoped DAO. Identity strings are stored exactly as supplied. */
public final class JdbcTestAutomationIdentityDao implements TestAutomationIdentityDao {
    private static final String SELECT = "SELECT id, project_id, source, namespace, external_key, created_at FROM test_automation_identities";
    private final Connection connection;
    public JdbcTestAutomationIdentityDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public Optional<TestAutomationIdentity> findById(Long id) {
        return query(SELECT + " WHERE id=?", id).stream().findFirst();
    }
    @Override public List<TestAutomationIdentity> listByProject(Long projectId) {
        return query(SELECT + " WHERE project_id=? ORDER BY source, namespace, external_key, id", projectId);
    }
    @Override public Optional<TestAutomationIdentity> findByIdForUpdate(Long id) {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Identity locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock TestAutomationIdentity", e); }
        return query(SELECT + " WHERE id=? FOR UPDATE", id).stream().findFirst();
    }
    private List<TestAutomationIdentity> query(String sql, Long id) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setObject(1, id, Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<TestAutomationIdentity> rows = new ArrayList<>();
                while (rs.next()) rows.add(map(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("Find TestAutomationIdentity", e); }
    }
    @Override public Optional<TestAutomationIdentity> findByExternalKey(Long projectId, AutomationSource source, String namespace, String externalKey) {
        try (PreparedStatement s = connection.prepareStatement(SELECT + " WHERE project_id=? AND source=? AND namespace=? AND external_key=?")) {
            bindIdentity(s, projectId, source, namespace, externalKey);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw new DataAccessException("Find external identity", e); }
    }
    @Override public TestAutomationIdentity insert(TestAutomationIdentity value) {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO test_automation_identities (project_id, source, namespace, external_key) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            bindIdentity(s, value.projectId(), value.source(), value.namespace(), value.externalKey());
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected identity insert count");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Inserted identity could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert TestAutomationIdentity", e); }
    }
    private static void bindIdentity(PreparedStatement s, Long projectId, AutomationSource source, String namespace, String externalKey) throws SQLException {
        s.setObject(1, projectId, Types.BIGINT);
        s.setString(2, source == null ? null : source.name());
        s.setString(3, namespace); s.setString(4, externalKey);
    }
    private static TestAutomationIdentity map(ResultSet rs) throws SQLException {
        try {
            return new TestAutomationIdentity(rs.getObject("id", Long.class), rs.getObject("project_id", Long.class),
                    AutomationSource.valueOf(rs.getString("source")), rs.getString("namespace"), rs.getString("external_key"),
                    rs.getObject("created_at", LocalDateTime.class));
        } catch (IllegalArgumentException e) { throw new SQLException("Unsupported automation source", "22000", e); }
    }
}
