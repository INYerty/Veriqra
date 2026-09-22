package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.TestPlanDao;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Non-owning, connection-scoped DAO; never share across threads or connection leases. */
public final class JdbcTestPlanDao implements TestPlanDao {
    private static final String COLUMNS = "id, project_id, key_no, name, description, status, created_by, created_at, updated_at, lock_version";
    private final Connection connection;

    public JdbcTestPlanDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public Optional<TestPlan> findById(Long id) {
        return query("SELECT " + COLUMNS + " FROM test_plans WHERE id=?", id).stream().findFirst();
    }

    @Override public Optional<TestPlan> findByIdForUpdate(Long id) {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Parent locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock TestPlan", e); }
        return query("SELECT " + COLUMNS + " FROM test_plans WHERE id=? FOR UPDATE", id).stream().findFirst();
    }

    @Override public Optional<TestPlan> findByKey(Long projectId, Long keyNo) {
        return query("SELECT " + COLUMNS + " FROM test_plans WHERE project_id=? AND key_no=?", projectId, keyNo).stream().findFirst();
    }

    @Override public List<TestPlan> listByProject(Long projectId) {
        return query("SELECT " + COLUMNS + " FROM test_plans WHERE project_id=? ORDER BY key_no", projectId);
    }

    private List<TestPlan> query(String sql, Long... values) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i], Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<TestPlan> rows = new ArrayList<>();
                while (rs.next()) rows.add(map(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("Find TestPlan", e); }
    }

    @Override public TestPlan insert(TestPlan value) {
        String sql = "INSERT INTO test_plans (project_id, key_no, name, description, status, created_by) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement s = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setObject(1, value.projectId(), Types.BIGINT);
            s.setObject(2, value.keyNo(), Types.BIGINT);
            s.setString(3, value.name());
            s.setString(4, value.description());
            s.setString(5, value.status() == null ? null : value.status().name());
            s.setObject(6, value.createdBy(), Types.BIGINT);
            if (s.executeUpdate() != 1) throw new SQLException("Insert affected an unexpected number of rows");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Inserted TestPlan could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert TestPlan", e); }
    }

    @Override public TestPlan update(TestPlan value) {
        JdbcValues.writableVersion(value.lockVersion());
        String sql = "UPDATE test_plans SET name=?, description=?, status=?, updated_at=CURRENT_TIMESTAMP(6), "
                + "lock_version=lock_version+1 WHERE id=? AND lock_version=?";
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setString(1, value.name());
            s.setString(2, value.description());
            s.setString(3, value.status() == null ? null : value.status().name());
            s.setObject(4, value.id(), Types.BIGINT);
            s.setInt(5, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(value.id()).orElseThrow(() -> new DataAccessException("Updated TestPlan could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update TestPlan", e); }
    }

    private static TestPlan map(ResultSet rs) throws SQLException {
        try {
            return new TestPlan(rs.getObject("id", Long.class), rs.getObject("project_id", Long.class), rs.getObject("key_no", Long.class), rs.getString("name"), rs.getString("description"), TestPlanStatus.valueOf(rs.getString("status")), rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class), JdbcValues.version(rs));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unsupported TestPlan enum value in database", "22000", e);
        }
    }
}
