package io.github.lz007001cn.qatrack.dao.jdbc;

import io.github.lz007001cn.qatrack.dao.DefectDao;
import io.github.lz007001cn.qatrack.exception.*;
import io.github.lz007001cn.qatrack.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Non-owning, connection-scoped DAO. Caller owns all transaction boundaries. */
public final class JdbcDefectDao implements DefectDao {
    private static final String SELECT = "SELECT id, project_id, key_no, title, description, severity, priority, status, reporter_id, assignee_id, resolution_note, created_at, updated_at, lock_version FROM defects";
    private final Connection connection;
    public JdbcDefectDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public Optional<Defect> findById(Long id) {
        return query(SELECT + " WHERE id=?", id).stream().findFirst();
    }

    @Override public Optional<Defect> findByIdForUpdate(Long id) {
        requireTransaction();
        return query(SELECT + " WHERE id=? FOR UPDATE", id).stream().findFirst();
    }

    @Override public Optional<Defect> findByKey(Long projectId, Long keyNo) {
        return query(SELECT + " WHERE project_id=? AND key_no=?", projectId, keyNo).stream().findFirst();
    }

    @Override public List<Defect> listByProject(Long projectId) {
        return query(SELECT + " WHERE project_id=? ORDER BY key_no", projectId);
    }

    private List<Defect> query(String sql, Long... values) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i], Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<Defect> rows = new ArrayList<>();
                while (rs.next()) rows.add(map(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("Find Defect", e); }
    }

    private void requireTransaction() {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Defect locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock Defect", e); }
    }

    @Override public Defect insert(Defect value) {
        String sql = "INSERT INTO defects (project_id, key_no, title, description, severity, priority, status, reporter_id, assignee_id, resolution_note) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement s = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setObject(1, value.projectId(), Types.BIGINT);
            s.setObject(2, value.keyNo(), Types.BIGINT);
            s.setString(3, value.title());
            s.setString(4, value.description());
            s.setString(5, value.severity() == null ? null : value.severity().name());
            s.setString(6, value.priority() == null ? null : value.priority().name());
            s.setString(7, value.status() == null ? null : value.status().name());
            s.setObject(8, value.reporterId(), Types.BIGINT);
            s.setObject(9, value.assigneeId(), Types.BIGINT);
            s.setString(10, value.resolutionNote());
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected insert count");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Inserted Defect could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert Defect", e); }
    }

    @Override public Defect update(Defect value) {
        JdbcValues.writableVersion(value.lockVersion());
        String sql = "UPDATE defects SET title=?, description=?, severity=?, priority=?, status=?, assignee_id=?, resolution_note=?, updated_at=CURRENT_TIMESTAMP(6), "
                + "lock_version=lock_version+1 WHERE id=? AND lock_version=?";
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setString(1, value.title());
            s.setString(2, value.description());
            s.setString(3, value.severity() == null ? null : value.severity().name());
            s.setString(4, value.priority() == null ? null : value.priority().name());
            s.setString(5, value.status() == null ? null : value.status().name());
            s.setObject(6, value.assigneeId(), Types.BIGINT);
            s.setString(7, value.resolutionNote());
            s.setObject(8, value.id(), Types.BIGINT);
            s.setObject(9, value.lockVersion(), Types.INTEGER);
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(value.id()).orElseThrow(() -> new DataAccessException("Updated Defect could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update Defect", e); }
    }

    private static Defect map(ResultSet rs) throws SQLException {
        try {
            return new Defect(rs.getObject("id", Long.class),
                    rs.getObject("project_id", Long.class),
                    rs.getObject("key_no", Long.class),
                    rs.getString("title"),
                    rs.getString("description"),
                    DefectSeverity.valueOf(rs.getString("severity")),
                    Priority.valueOf(rs.getString("priority")),
                    DefectStatus.valueOf(rs.getString("status")),
                    rs.getObject("reporter_id", Long.class),
                    rs.getObject("assignee_id", Long.class),
                    rs.getString("resolution_note"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class),
                    JdbcValues.version(rs));
        } catch (IllegalArgumentException e) { throw new SQLException("Unsupported Defect enum", "22000", e); }
    }
}
