package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.ProjectDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.exception.OptimisticLockException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/** Non-owning, connection-scoped DAO. Do not share this instance across threads or connection leases. */
public final class JdbcProjectDao implements ProjectDao {
    private static final String COLUMNS = "id, project_key, name, description, status, created_by, created_at, updated_at, lock_version";
    private final Connection connection;

    public JdbcProjectDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public java.util.List<Project> listAll() {
        try (PreparedStatement s = connection.prepareStatement("SELECT " + COLUMNS + " FROM projects ORDER BY id");
             ResultSet rs = s.executeQuery()) {
            var rows = new java.util.ArrayList<Project>();
            while (rs.next()) rows.add(map(rs));
            return java.util.List.copyOf(rows);
        } catch (SQLException e) { throw new DataAccessException("List Projects", e); }
    }

    @Override public Optional<Project> findById(Long id) {
        return find("SELECT " + COLUMNS + " FROM projects WHERE id=?", id, Types.BIGINT);
    }

    @Override public Optional<Project> findByIdForShare(Long id) {
        requireTransaction();
        return find("SELECT " + COLUMNS + " FROM projects WHERE id=? FOR SHARE", id, Types.BIGINT);
    }

    @Override public Optional<Project> findByIdForUpdate(Long id) {
        requireTransaction();
        return find("SELECT " + COLUMNS + " FROM projects WHERE id=? FOR UPDATE", id, Types.BIGINT);
    }

    @Override public Optional<Project> findByKey(String key) {
        return find("SELECT " + COLUMNS + " FROM projects WHERE project_key=?", key, Types.VARCHAR);
    }

    private Optional<Project> find(String sql, Object key, int type) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setObject(1, key, type);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new DataAccessException("Find Project", e); }
    }

    private void requireTransaction() {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Project locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock Project", e); }
    }

    @Override public Project insert(Project value) {
        String sql = "INSERT INTO projects (project_key, name, description, status, created_by) VALUES (?,?,?,?,?)";
        try (PreparedStatement s = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, value.projectKey());
            s.setString(2, value.name());
            s.setString(3, value.description());
            s.setString(4, value.status() == null ? null : value.status().name());
            s.setObject(5, value.createdBy(), Types.BIGINT);
            if (s.executeUpdate() != 1) throw new SQLException("Insert affected an unexpected number of rows");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Inserted Project could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert Project", e); }
    }

    @Override public Project update(Project value) {
        JdbcValues.writableVersion(value.lockVersion());
        String sql = "UPDATE projects SET name=?, description=?, status=?, updated_at=CURRENT_TIMESTAMP(6), "
                + "lock_version=lock_version+1 WHERE id=? AND lock_version=?";
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setString(1, value.name());
            s.setString(2, value.description());
            s.setString(3, value.status() == null ? null : value.status().name());
            s.setObject(4, value.id(), Types.BIGINT);
            s.setInt(5, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(value.id()).orElseThrow(() -> new DataAccessException("Updated Project could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update Project", e); }
    }

    private static Project map(ResultSet rs) throws SQLException {
        try {
            return new Project(rs.getObject("id", Long.class), rs.getString("project_key"), rs.getString("name"),
                    rs.getString("description"), ProjectStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class), JdbcValues.version(rs));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unsupported Project enum value in database", "22000", e);
        }
    }
}
