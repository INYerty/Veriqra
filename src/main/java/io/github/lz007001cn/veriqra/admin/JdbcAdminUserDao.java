package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Connection-scoped, non-owning Administration user queries. */
public final class JdbcAdminUserDao implements AdminUserDao {
    private static final String VIEW = "SELECT u.id,u.username,u.display_name,u.system_role,u.status,u.lock_version,u.created_at,"
            + "(SELECT MAX(created_at) FROM login_events WHERE user_id=u.id AND result='SUCCESS') last_login,"
            + "(SELECT MAX(created_at) FROM access_logs WHERE user_id=u.id) last_seen,"
            + "(SELECT ip_address FROM login_events WHERE user_id=u.id AND result='SUCCESS' ORDER BY created_at DESC,id DESC LIMIT 1) last_ip,"
            + "(SELECT device_type FROM login_events WHERE user_id=u.id AND result='SUCCESS' ORDER BY created_at DESC,id DESC LIMIT 1) last_device,"
            + "COALESCE(c.balance,0) credit_balance FROM users u LEFT JOIN credit_accounts c ON c.user_id=u.id ";
    private final Connection connection;
    public JdbcAdminUserDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public Page<AdminUserView> list(int page, int pageSize) {
        try (var count = connection.prepareStatement("SELECT COUNT(*) FROM users");
             var statement = connection.prepareStatement(VIEW + "ORDER BY u.id DESC LIMIT ? OFFSET ?")) {
            long total;
            try (var rs=count.executeQuery()) { rs.next(); total=rs.getLong(1); }
            statement.setInt(1,pageSize); statement.setInt(2,Math.multiplyExact(page-1,pageSize));
            List<AdminUserView> rows=new ArrayList<>();
            try (var rs=statement.executeQuery()) { while(rs.next()) rows.add(map(rs)); }
            return new Page<>(rows,total,page,pageSize);
        } catch (SQLException e) { throw new DataAccessException("List admin users",e); }
    }

    @Override public Optional<AdminUserView> find(long id) {
        try(var statement=connection.prepareStatement(VIEW+"WHERE u.id=?")) {
            statement.setLong(1,id);
            try(var rs=statement.executeQuery()) { return rs.next()?Optional.of(map(rs)):Optional.empty(); }
        } catch(SQLException e) { throw new DataAccessException("Find admin user",e); }
    }

    @Override public List<Long> lockActiveAdminIds() {
        try {
            if(connection.getAutoCommit()) throw new SQLException("ADMIN lock requires transaction");
            try(var statement=connection.prepareStatement("SELECT id FROM users WHERE system_role='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE");
                var rs=statement.executeQuery()) {
                List<Long> ids=new ArrayList<>(); while(rs.next()) ids.add(rs.getLong(1)); return ids;
            }
        } catch(SQLException e) { throw new DataAccessException("Lock active admins",e); }
    }

    @Override public List<Long> activeUserIds(int maximum) {
        try(var statement=connection.prepareStatement("SELECT id FROM users WHERE status='ACTIVE' ORDER BY id LIMIT ?")) {
            statement.setInt(1,maximum+1);
            try(var rs=statement.executeQuery()) {
                List<Long> ids=new ArrayList<>(); while(rs.next()) ids.add(rs.getLong(1)); return ids;
            }
        } catch(SQLException e) { throw new DataAccessException("List active user IDs",e); }
    }

    private static AdminUserView map(ResultSet rs) throws SQLException {
        return new AdminUserView(rs.getLong("id"),rs.getString("username"),rs.getString("display_name"),
                SystemRole.valueOf(rs.getString("system_role")),UserStatus.valueOf(rs.getString("status")),
                rs.getInt("lock_version"),rs.getObject("created_at",LocalDateTime.class),
                rs.getObject("last_login",LocalDateTime.class),rs.getObject("last_seen",LocalDateTime.class),
                rs.getString("last_ip"),rs.getString("last_device"),rs.getLong("credit_balance"));
    }
}
