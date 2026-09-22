package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.UserDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.exception.OptimisticLockException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/** Non-owning, connection-scoped DAO. Do not share this instance across threads or connection leases. */
public final class JdbcUserDao implements UserDao {
    private static final String COLUMNS = "id, username, display_name, password_hash, system_role, status, created_at, updated_at, lock_version";
    private final Connection connection;

    public JdbcUserDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public Optional<User> findById(Long id) {
        return find("SELECT " + COLUMNS + " FROM users WHERE id=?", id, Types.BIGINT);
    }

    @Override public Optional<User> findByIdForShare(Long id) {
        requireTransaction();
        return find("SELECT " + COLUMNS + " FROM users WHERE id=? FOR SHARE", id, Types.BIGINT);
    }

    @Override public Optional<User> findByUsername(String key) {
        return find("SELECT " + COLUMNS + " FROM users WHERE username=?", key, Types.VARCHAR);
    }

    private Optional<User> find(String sql, Object key, int type) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setObject(1, key, type);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new DataAccessException("Find User", e); }
    }

    private void requireTransaction() {
        try {
            if (connection.getAutoCommit()) throw new SQLException("User locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock User", e); }
    }

    @Override public User insert(User value) {
        String sql = "INSERT INTO users (username, display_name, password_hash, system_role, status) VALUES (?,?,?,?,?)";
        try (PreparedStatement s = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, value.username());
            s.setString(2, value.displayName());
            s.setString(3, value.passwordHash());
            s.setString(4, value.systemRole() == null ? null : value.systemRole().name());
            s.setString(5, value.status() == null ? null : value.status().name());
            if (s.executeUpdate() != 1) throw new SQLException("Insert affected an unexpected number of rows");
            long id;
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated key");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow(() -> new DataAccessException("Inserted User could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Insert User", e); }
    }

    @Override public User update(User value) {
        JdbcValues.writableVersion(value.lockVersion());
        String sql = "UPDATE users SET display_name=?, password_hash=?, system_role=?, status=?, updated_at=CURRENT_TIMESTAMP(6), "
                + "lock_version=lock_version+1 WHERE id=? AND lock_version=?";
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            s.setString(1, value.displayName());
            s.setString(2, value.passwordHash());
            s.setString(3, value.systemRole() == null ? null : value.systemRole().name());
            s.setString(4, value.status() == null ? null : value.status().name());
            s.setObject(5, value.id(), Types.BIGINT);
            s.setInt(6, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(value.id()).orElseThrow(() -> new DataAccessException("Updated User could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update User", e); }
    }

    private static User map(ResultSet rs) throws SQLException {
        try {
            return new User(rs.getObject("id", Long.class), rs.getString("username"), rs.getString("display_name"),
                    rs.getString("password_hash"), SystemRole.valueOf(rs.getString("system_role")),
                    UserStatus.valueOf(rs.getString("status")), rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class), JdbcValues.version(rs));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unsupported User enum value in database", "22000", e);
        }
    }
}
