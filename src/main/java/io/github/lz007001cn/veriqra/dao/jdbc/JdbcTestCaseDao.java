package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.TestCaseDao;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Non-owning, connection-scoped DAO; never share across threads or connection leases. */
public final class JdbcTestCaseDao implements TestCaseDao {
    private static final String COLUMNS = "id, project_id, key_no, title, description, preconditions, priority, status, created_by, created_at, updated_at, lock_version";
    private final Connection connection;

    public JdbcTestCaseDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public Optional<TestCase> findById(Long id) {
        return query("SELECT " + COLUMNS + " FROM test_cases WHERE id=?", id).stream().findFirst();
    }

    @Override public Optional<TestCase> findByIdForUpdate(Long id) {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Parent locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock TestCase", e); }
        return query("SELECT " + COLUMNS + " FROM test_cases WHERE id=? FOR UPDATE", id).stream().findFirst();
    }

    @Override public Optional<TestCase> findByKey(Long projectId, Long keyNo) {
        return query("SELECT " + COLUMNS + " FROM test_cases WHERE project_id=? AND key_no=?", projectId, keyNo).stream().findFirst();
    }

    @Override public List<TestCase> listByProject(Long projectId) {
        return query("SELECT " + COLUMNS + " FROM test_cases WHERE project_id=? ORDER BY key_no", projectId);
    }

    private List<TestCase> query(String sql, Long... values) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i], Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<TestCase> rows = new ArrayList<>();
                while (rs.next()) rows.add(map(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("Find TestCase", e); }
    }

    @Override public TestCase insert(TestCase value) {
        String sql = "INSERT INTO test_cases (project_id, key_no, title, description, preconditions, priority, status, created_by) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement s = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setObject(1, value.projectId(), Types.BIGINT);
            s.setObject(2, value.keyNo(), Types.BIGINT);
            s.setString(3, value.title());
            s.setString(4, value.description());
            s.setString(5, value.preconditions());
            s.setString(6, value.priority() == null ? null : value.priority().name());
            s.setString(7, value.status() == null ? null : value.status().name());
            s.setObject(8, value.createdBy(), Types.BIGINT);
            if (s.executeUpdate() != 1) throw new SQLException("Insert affected an unexpected number of rows");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Inserted TestCase could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert TestCase", e); }
    }

    @Override public TestCase update(TestCase value) {
        JdbcValues.writableVersion(value.lockVersion());
        String sql = "UPDATE test_cases SET title=?, description=?, preconditions=?, priority=?, status=?, updated_at=CURRENT_TIMESTAMP(6), "
                + "lock_version=lock_version+1 WHERE id=? AND lock_version=?";
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setString(1, value.title());
            s.setString(2, value.description());
            s.setString(3, value.preconditions());
            s.setString(4, value.priority() == null ? null : value.priority().name());
            s.setString(5, value.status() == null ? null : value.status().name());
            s.setObject(6, value.id(), Types.BIGINT);
            s.setInt(7, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(value.id()).orElseThrow(() -> new DataAccessException("Updated TestCase could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update TestCase", e); }
    }

    private static TestCase map(ResultSet rs) throws SQLException {
        try {
            return new TestCase(rs.getObject("id", Long.class), rs.getObject("project_id", Long.class), rs.getObject("key_no", Long.class), rs.getString("title"), rs.getString("description"), rs.getString("preconditions"), Priority.valueOf(rs.getString("priority")), TestCaseStatus.valueOf(rs.getString("status")), rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class), JdbcValues.version(rs));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unsupported TestCase enum value in database", "22000", e);
        }
    }
}
