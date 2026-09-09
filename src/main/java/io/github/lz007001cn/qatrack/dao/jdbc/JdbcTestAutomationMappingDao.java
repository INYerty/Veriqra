package io.github.lz007001cn.qatrack.dao.jdbc;

import io.github.lz007001cn.qatrack.dao.TestAutomationMappingDao;
import io.github.lz007001cn.qatrack.exception.*;
import io.github.lz007001cn.qatrack.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Non-owning DAO; checking same project, history and permissions belongs to the caller. */
public final class JdbcTestAutomationMappingDao implements TestAutomationMappingDao {
    private static final String SELECT = "SELECT id, automation_identity_id, test_case_id, status, created_by, created_at, updated_at, lock_version FROM test_automation_mappings";
    private final Connection connection;
    public JdbcTestAutomationMappingDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }
    @Override public Optional<TestAutomationMapping> findById(Long id) {
        return query(SELECT + " WHERE id=?", id).stream().findFirst();
    }
    @Override public Optional<TestAutomationMapping> findByIdentity(Long identityId) {
        return query(SELECT + " WHERE automation_identity_id=?", identityId).stream().findFirst();
    }
    @Override public boolean existsRecord(Long identityId) { return findByIdentity(identityId).isPresent(); }
    @Override public List<TestAutomationMapping> listByTestCase(Long caseId) {
        return query(SELECT + " WHERE test_case_id=? ORDER BY automation_identity_id", caseId);
    }
    @Override public Optional<TestAutomationMapping> findByIdentityForUpdate(Long identityId) {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Mapping locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock TestAutomationMapping", e); }
        return query(SELECT + " WHERE automation_identity_id=? FOR UPDATE", identityId).stream().findFirst();
    }
    private List<TestAutomationMapping> query(String sql, Long id) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setObject(1, id, Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<TestAutomationMapping> rows = new ArrayList<>();
                while (rs.next()) rows.add(map(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("Find TestAutomationMapping", e); }
    }
    @Override public TestAutomationMapping add(TestAutomationMapping value) {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO test_automation_mappings (automation_identity_id, test_case_id, status, created_by) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setObject(1, value.automationIdentityId(), Types.BIGINT);
            s.setObject(2, value.testCaseId(), Types.BIGINT);
            s.setString(3, value.status() == null ? null : value.status().name());
            s.setObject(4, value.createdBy(), Types.BIGINT);
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected mapping insert count");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Added mapping could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Add TestAutomationMapping", e); }
    }
    @Override public TestAutomationMapping update(TestAutomationMapping value) {
        JdbcValues.writableVersion(value.lockVersion());
        try (PreparedStatement s = connection.prepareStatement(
                "UPDATE test_automation_mappings SET test_case_id=?, status=?, updated_at=CURRENT_TIMESTAMP(6), "
                        + "lock_version=lock_version+1 WHERE id=? AND lock_version=?")) {
            s.setObject(1, value.testCaseId(), Types.BIGINT);
            s.setString(2, value.status() == null ? null : value.status().name());
            s.setObject(3, value.id(), Types.BIGINT); s.setInt(4, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(value.id()).orElseThrow(() -> new DataAccessException("Updated mapping could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update TestAutomationMapping", e); }
    }
    private static TestAutomationMapping map(ResultSet rs) throws SQLException {
        try {
            return new TestAutomationMapping(rs.getObject("id", Long.class), rs.getObject("automation_identity_id", Long.class),
                    rs.getObject("test_case_id", Long.class), AutomationMappingStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class), JdbcValues.version(rs));
        } catch (IllegalArgumentException e) { throw new SQLException("Unsupported mapping status", "22000", e); }
    }
}
