package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.ProjectCounterDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.util.*;

/** Uses the caller's transaction and connection; never commits or rolls back. */
public final class JdbcProjectCounterDao implements ProjectCounterDao {
    private final Connection connection;
    public JdbcProjectCounterDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public ProjectCounter insert(ProjectCounter value) {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO project_counters (project_id, entity_type, next_value) VALUES (?,?,?)")) {
            bindKey(s, value.projectId(), value.entityType());
            s.setObject(3, value.nextValue(), Types.BIGINT);
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected counter insert count");
            return find(value.projectId(), value.entityType()).orElseThrow(() -> new DataAccessException("Inserted counter could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert ProjectCounter", e); }
    }

    @Override public Optional<ProjectCounter> find(Long projectId, CounterEntityType type) {
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT project_id, entity_type, next_value FROM project_counters WHERE project_id=? AND entity_type=?")) {
            bindKey(s, projectId, type);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw new DataAccessException("Find ProjectCounter", e); }
    }

    @Override public List<ProjectCounter> listByProject(Long projectId) {
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT project_id, entity_type, next_value FROM project_counters WHERE project_id=? ORDER BY entity_type")) {
            s.setObject(1, projectId, Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<ProjectCounter> rows = new ArrayList<>();
                while (rs.next()) rows.add(map(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("List ProjectCounter", e); }
    }

    @Override public Long allocateNext(Long projectId, CounterEntityType type) {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Counter allocation requires an outer transaction", "25000");
            long next;
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT next_value FROM project_counters WHERE project_id=? AND entity_type=? FOR UPDATE")) {
                bindKey(s, projectId, type);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Counter must be initialized before allocation", "02000");
                    next = rs.getLong(1);
                }
            }
            if (next <= 0 || next == Long.MAX_VALUE) throw new SQLException("Counter allocation would exceed positive BIGINT range", "22003");
            try (PreparedStatement s = connection.prepareStatement(
                    "UPDATE project_counters SET next_value=next_value+1 WHERE project_id=? AND entity_type=?")) {
                bindKey(s, projectId, type);
                if (s.executeUpdate() != 1) throw new SQLException("Locked counter row disappeared");
            }
            return next;
        } catch (SQLException e) { throw new DataAccessException("Allocate ProjectCounter", e); }
    }

    private static void bindKey(PreparedStatement s, Long projectId, CounterEntityType type) throws SQLException {
        s.setObject(1, projectId, Types.BIGINT);
        s.setString(2, type == null ? null : type.name());
    }

    private static ProjectCounter map(ResultSet rs) throws SQLException {
        try {
            return new ProjectCounter(rs.getObject("project_id", Long.class),
                    CounterEntityType.valueOf(rs.getString("entity_type")), rs.getObject("next_value", Long.class));
        } catch (IllegalArgumentException e) { throw new SQLException("Unsupported counter type", "22000", e); }
    }
}
