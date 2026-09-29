package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Append-only audit/telemetry writes and bounded, newest-first admin queries. */
public final class JdbcAdminLogDao implements AdminLogDao {
    private final Connection connection;
    public JdbcAdminLogDao(Connection connection) { this.connection=Objects.requireNonNull(connection); }

    @Override public void appendLogin(LoginEvent e) {
        try(var s=connection.prepareStatement("INSERT INTO login_events(user_id,username_attempted,ip_address,user_agent,browser,operating_system,device_type,result,failure_reason) VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setObject(1,e.userId(),Types.BIGINT);s.setString(2,e.usernameAttempted());s.setString(3,e.ipAddress());
            s.setString(4,e.userAgent());s.setString(5,e.browser());s.setString(6,e.operatingSystem());
            s.setString(7,e.deviceType());s.setString(8,e.result());s.setString(9,e.failureReason());s.executeUpdate();
        } catch(SQLException failure) { throw new DataAccessException("Append login event",failure); }
    }

    @Override public void appendAccess(AccessEvent e) {
        try(var s=connection.prepareStatement("INSERT INTO access_logs(user_id,ip_address,user_agent,browser,operating_system,device_type,http_method,request_path,status_code,request_id,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setObject(1,e.userId(),Types.BIGINT);s.setString(2,e.ipAddress());s.setString(3,e.userAgent());
            s.setString(4,e.browser());s.setString(5,e.operatingSystem());s.setString(6,e.deviceType());
            s.setString(7,e.httpMethod());s.setString(8,e.requestPath());s.setInt(9,e.statusCode());
            s.setString(10,e.requestId());s.setInt(11,e.durationMs());s.executeUpdate();
        } catch(SQLException failure) { throw new DataAccessException("Append access event",failure); }
    }

    @Override public void appendAudit(AuditEvent e) {
        try(var s=connection.prepareStatement("INSERT INTO audit_logs(actor_user_id,action,target_type,target_id,summary,metadata_json,ip_address,request_id) VALUES(?,?,?,?,?,?,?,?)")) {
            s.setLong(1,e.actorUserId());s.setString(2,e.action());s.setString(3,e.targetType());
            s.setObject(4,e.targetId(),Types.BIGINT);s.setString(5,e.summary());s.setString(6,e.metadataJson());
            s.setString(7,e.ipAddress());s.setString(8,e.requestId());s.executeUpdate();
        } catch(SQLException failure) { throw new DataAccessException("Append admin audit",failure); }
    }

    @Override public Page<LoginEvent> logins(LogFilter f,int page,int size) {
        var q=new Conditions();q.add("created_at>=?",f.from());q.add("created_at<?",f.to());
        q.add("username_attempted=?",f.username());q.add("result=?",f.result());q.add("ip_address=?",f.ip());
        q.add("device_type=?",f.deviceType());
        return query("login_events","id,user_id,username_attempted,ip_address,user_agent,browser,operating_system,device_type,result,failure_reason,created_at",q,page,size,
                rs->new LoginEvent(rs.getLong(1),rs.getObject(2,Long.class),rs.getString(3),rs.getString(4),rs.getString(5),
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getObject(11,LocalDateTime.class)));
    }

    @Override public Page<SessionActivity> sessionActivity(int page,int size) {
        String sql="SELECT l.id,l.user_id,l.username_attempted,l.created_at,"
                +"(SELECT MAX(a.created_at) FROM access_logs a WHERE a.user_id=l.user_id) user_last_seen,"
                +"l.ip_address,l.device_type FROM login_events l WHERE l.result='SUCCESS' "
                +"ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?";
        try(var count=connection.prepareStatement("SELECT COUNT(*) FROM login_events WHERE result='SUCCESS'");
            var statement=connection.prepareStatement(sql)) {
            long total;try(var rs=count.executeQuery()){rs.next();total=rs.getLong(1);}
            statement.setInt(1,size);statement.setInt(2,Math.multiplyExact(page-1,size));
            List<SessionActivity> rows=new ArrayList<>();
            try(var rs=statement.executeQuery()) {
                while(rs.next()) rows.add(new SessionActivity(rs.getLong(1),rs.getObject(2,Long.class),rs.getString(3),
                        rs.getObject(4,LocalDateTime.class),rs.getObject(5,LocalDateTime.class),rs.getString(6),rs.getString(7)));
            }
            return new Page<>(rows,total,page,size);
        } catch(SQLException e) { throw new DataAccessException("Query login activity",e); }
    }

    @Override public Page<AccessEvent> access(LogFilter f,int page,int size) {
        var q=new Conditions();q.add("a.created_at>=?",f.from());q.add("a.created_at<?",f.to());
        q.add("u.username=?",f.username());q.add("a.ip_address=?",f.ip());q.add("a.device_type=?",f.deviceType());
        q.add("a.status_code=?",f.statusCode());q.add("a.http_method=?",f.method());
        return query("access_logs a LEFT JOIN users u ON u.id=a.user_id",
                "a.id,a.user_id,u.username,a.ip_address,a.user_agent,a.browser,a.operating_system,a.device_type,a.http_method,a.request_path,a.status_code,a.request_id,a.duration_ms,a.created_at",q,page,size,
                rs->new AccessEvent(rs.getLong(1),rs.getObject(2,Long.class),rs.getString(3),rs.getString(4),rs.getString(5),
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getInt(11),
                        rs.getString(12),rs.getInt(13),rs.getObject(14,LocalDateTime.class)));
    }

    @Override public Page<AuditEvent> audits(LogFilter f,int page,int size) {
        var q=new Conditions();q.add("a.created_at>=?",f.from());q.add("a.created_at<?",f.to());
        q.add("a.actor_user_id=?",f.actorId());q.add("a.action=?",f.action());
        return query("audit_logs a","a.id,a.actor_user_id,a.action,a.target_type,a.target_id,a.summary,a.metadata_json,a.ip_address,a.request_id,a.created_at",q,page,size,
                rs->new AuditEvent(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getObject(5,Long.class),
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getObject(10,LocalDateTime.class)));
    }

    @FunctionalInterface private interface Mapper<T> { T map(ResultSet rs)throws SQLException; }
    private <T> Page<T> query(String table,String fields,Conditions q,int page,int size,Mapper<T> map) {
        String filter=q.sql();
        try(var count=connection.prepareStatement("SELECT COUNT(*) FROM "+table+filter);
            var s=connection.prepareStatement("SELECT "+fields+" FROM "+table+filter+" ORDER BY "+(table.startsWith("login_")?"created_at DESC,id DESC":"a.created_at DESC,a.id DESC")+" LIMIT ? OFFSET ?")) {
            q.bind(count);q.bind(s);s.setInt(q.values.size()+1,size);s.setInt(q.values.size()+2,Math.multiplyExact(page-1,size));
            long total;try(var rs=count.executeQuery()){rs.next();total=rs.getLong(1);}
            List<T> rows=new ArrayList<>();try(var rs=s.executeQuery()){while(rs.next())rows.add(map.map(rs));}
            return new Page<>(rows,total,page,size);
        } catch(SQLException e) { throw new DataAccessException("Query admin log",e); }
    }
    private static final class Conditions {
        final StringBuilder sql=new StringBuilder(" WHERE 1=1");final List<Object> values=new ArrayList<>();
        void add(String condition,Object value){if(value!=null){sql.append(" AND ").append(condition);values.add(value);}}
        String sql(){return sql.toString();}
        void bind(PreparedStatement s)throws SQLException {for(int i=0;i<values.size();i++)s.setObject(i+1,values.get(i));}
    }

    @Override public AdminMetrics metrics(AdminTodayWindow today) {
        Objects.requireNonNull(today);
        String inToday="created_at>=? AND created_at<?";
        String sql="SELECT (SELECT COUNT(*) FROM users),(SELECT COUNT(*) FROM users WHERE status='ACTIVE'),"
                +"(SELECT COUNT(*) FROM users WHERE status='DISABLED'),(SELECT COUNT(*) FROM users WHERE system_role='ADMIN'),"
                +"(SELECT COUNT(*) FROM login_events WHERE result='SUCCESS' AND "+inToday+"),"
                +"(SELECT COUNT(*) FROM login_events WHERE result='FAILURE' AND "+inToday+"),"
                +"(SELECT COUNT(*) FROM login_events WHERE result='RATE_LIMITED' AND "+inToday+"),"
                +"(SELECT COUNT(*) FROM access_logs WHERE "+inToday+"),"
                +"(SELECT COUNT(*) FROM access_logs WHERE status_code BETWEEN 400 AND 499 AND "+inToday+"),"
                +"(SELECT COUNT(*) FROM access_logs WHERE status_code BETWEEN 500 AND 599 AND "+inToday+"),"
                +"(SELECT COUNT(DISTINCT ip_address) FROM access_logs WHERE "+inToday+"),"
                +"(SELECT COALESCE(SUM(amount),0) FROM credit_transactions WHERE type='GRANT' AND "+inToday+"),"
                +"(SELECT COALESCE(SUM(amount),0) FROM credit_transactions WHERE type='RECLAIM' AND "+inToday+")";
        try(var s=connection.prepareStatement(sql)) {
            for(int i=1;i<=18;i+=2) {
                s.setObject(i,today.startUtc());
                s.setObject(i+1,today.nextStartUtc());
            }
            try(var rs=s.executeQuery()) {
            rs.next();return new AdminMetrics(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),
                    rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10),rs.getLong(11),
                    rs.getBigDecimal(12).toBigIntegerExact(),rs.getBigDecimal(13).toBigIntegerExact());
            }
        } catch(SQLException e) { throw new DataAccessException("Admin metrics",e); }
    }

    @Override public String databaseVersion() {
        try(var s=connection.prepareStatement("SELECT VERSION()");var rs=s.executeQuery()){rs.next();return rs.getString(1);}
        catch(SQLException e){throw new DataAccessException("Database version",e);}
    }
    @Override public int databaseTableCount() {
        try (var s=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()" );
             var rs=s.executeQuery()) { rs.next(); return rs.getInt(1); }
        catch(SQLException e){throw new DataAccessException("Database table count",e);}
    }
}
