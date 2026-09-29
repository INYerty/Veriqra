package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.TaskCreditDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Never owns the Connection or transaction. */
public final class JdbcTaskCreditDao implements TaskCreditDao {
    private static final String TRANSFER = "SELECT id,project_id,sender_user_id,recipient_user_id,amount,kind,offer_id,note,created_at FROM credit_transfers";
    private static final String OFFER = "SELECT id,request_key,project_id,task_id,from_user_id,to_user_id,credit_amount,note,status,created_at,resolved_at FROM task_handoff_offers";
    private final Connection connection;
    public JdbcTaskCreditDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }
    @FunctionalInterface private interface Bind { void apply(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface Row<T> { T map(ResultSet result) throws SQLException; }
    private <T> List<T> query(String operation, String sql, Bind bind, Row<T> row) {
        try (var statement = connection.prepareStatement(sql)) {
            bind.apply(statement);
            try (var result = statement.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (result.next()) values.add(row.map(result));
                return List.copyOf(values);
            }
        } catch (SQLException e) { throw new DataAccessException(operation, e); }
    }
    private <T> Optional<T> one(String operation, String sql, Bind bind, Row<T> row) {
        return query(operation, sql, bind, row).stream().findFirst();
    }
    private void write(String operation, String sql, Bind bind) {
        try (var statement = connection.prepareStatement(sql)) {
            bind.apply(statement);
            if (statement.executeUpdate() != 1) throw new SQLException("Unexpected update count");
        } catch (SQLException e) { throw new DataAccessException(operation, e); }
    }
    private void requireTransaction() {
        try { if (connection.getAutoCommit()) throw new SQLException("Outer transaction required", "25000"); }
        catch (SQLException e) { throw new DataAccessException("Lock credit workflow", e); }
    }
    @Override public Optional<CreditTransfer> findTransfer(String id) {
        return one("Find credit transfer", TRANSFER + " WHERE id=?", s -> s.setString(1, id), JdbcTaskCreditDao::transfer);
    }
    @Override public CreditTransfer insertTransfer(String id, long projectId, long senderId, long recipientId,
                                                   long amount, String kind, Long offerId, String note) {
        write("Insert credit transfer", "INSERT INTO credit_transfers(id,project_id,sender_user_id,recipient_user_id,amount,kind,offer_id,note) VALUES(?,?,?,?,?,?,?,?)",
                s -> { s.setString(1, id); s.setLong(2, projectId); s.setLong(3, senderId); s.setLong(4, recipientId);
                    s.setLong(5, amount); s.setString(6, kind); s.setObject(7, offerId, Types.BIGINT); s.setString(8, note); });
        return findTransfer(id).orElseThrow(() -> new DataAccessException("Inserted credit transfer not found"));
    }
    @Override public List<CreditTransfer> listTransfers(long userId, int limit) {
        return query("List credit transfers", TRANSFER + " WHERE sender_user_id=? OR recipient_user_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                s -> { s.setLong(1, userId); s.setLong(2, userId); s.setInt(3, limit); }, JdbcTaskCreditDao::transfer);
    }
    @Override public void appendLedger(long userId, long amount, String type, long actorId, String note,
                                       long projectId, String transferId, Long taskId) {
        write("Append project credit ledger", "INSERT INTO credit_transactions(user_id,amount,type,actor_user_id,reason,project_id,transfer_id,task_id) VALUES(?,?,?,?,?,?,?,?)",
                s -> { s.setLong(1, userId); s.setLong(2, amount); s.setString(3, type); s.setLong(4, actorId);
                    s.setString(5, note); s.setLong(6, projectId); s.setString(7, transferId); s.setObject(8, taskId, Types.BIGINT); });
    }
    @Override public void appendContribution(long projectId, long userId, long taskId, LocalDateTime acceptedAtUtc) {
        write("Append task contribution", "INSERT INTO contribution_events(project_id,user_id,task_id,points,event_type,created_at) VALUES(?,?,?,1,'TASK_ACCEPTED',?)",
                s -> { s.setLong(1, projectId); s.setLong(2, userId); s.setLong(3, taskId); s.setObject(4, acceptedAtUtc); });
    }
    @Override public List<MonthlyContribution> monthlyContribution(long projectId, LocalDateTime fromUtc, LocalDateTime toUtc) {
        return query("Monthly contribution", "SELECT m.user_id,u.username,u.display_name,COUNT(e.id) score,COUNT(e.id) accepted_task_count FROM project_members m JOIN users u ON u.id=m.user_id LEFT JOIN contribution_events e ON e.project_id=m.project_id AND e.user_id=m.user_id AND e.created_at>=? AND e.created_at<? WHERE m.project_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' GROUP BY m.user_id,u.username,u.display_name ORDER BY score DESC,u.username,m.user_id",
                s -> { s.setObject(1, fromUtc); s.setObject(2, toUtc); s.setLong(3, projectId); },
                r -> new MonthlyContribution(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getLong(5)));
    }
    @Override public Optional<TaskHandoffOffer> findOffer(long id) {
        return one("Find handoff offer", OFFER + " WHERE id=?", s -> s.setLong(1, id), JdbcTaskCreditDao::offer);
    }
    @Override public Optional<TaskHandoffOffer> lockOffer(long id) {
        requireTransaction();
        return one("Lock handoff offer", OFFER + " WHERE id=? FOR UPDATE", s -> s.setLong(1, id), JdbcTaskCreditDao::offer);
    }
    @Override public Optional<TaskHandoffOffer> findOfferByRequestKey(String key) {
        return one("Find handoff request", OFFER + " WHERE request_key=?", s -> s.setString(1, key), JdbcTaskCreditDao::offer);
    }
    @Override public TaskHandoffOffer insertOffer(String key, long projectId, long taskId, long fromId, long toId,
                                                   long amount, String note) {
        try (var s = connection.prepareStatement("INSERT INTO task_handoff_offers(request_key,project_id,task_id,from_user_id,to_user_id,credit_amount,note) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, key); s.setLong(2, projectId); s.setLong(3, taskId); s.setLong(4, fromId);
            s.setLong(5, toId); s.setLong(6, amount); s.setString(7, note);
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected insert count");
            try (var keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing handoff ID");
                return findOffer(keys.getLong(1)).orElseThrow(() -> new DataAccessException("Inserted handoff not found"));
            }
        } catch (SQLException e) { throw new DataAccessException("Insert handoff offer", e); }
    }
    @Override public List<TaskHandoffOffer> listOffers(long projectId, long actorId) {
        return query("List handoff offers", OFFER + " WHERE project_id=? AND (from_user_id=? OR to_user_id=?) ORDER BY id DESC LIMIT 100",
                s -> { s.setLong(1, projectId); s.setLong(2, actorId); s.setLong(3, actorId); }, JdbcTaskCreditDao::offer);
    }
    @Override public void resolveOffer(long id, String status) {
        write("Resolve handoff offer", "UPDATE task_handoff_offers SET status=?,resolved_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PENDING'",
                s -> { s.setString(1, status); s.setLong(2, id); });
    }
    @Override public void cancelPendingOffersForTask(long taskId) {
        try (var s = connection.prepareStatement("UPDATE task_handoff_offers SET status='CANCELLED',resolved_at=CURRENT_TIMESTAMP(6) WHERE task_id=? AND status='PENDING'")) {
            s.setLong(1, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new DataAccessException("Cancel stale handoff offers", e); }
    }
    private static CreditTransfer transfer(ResultSet r) throws SQLException {
        return new CreditTransfer(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5),
                r.getString(6), r.getObject(7, Long.class), r.getString(8), r.getObject(9, LocalDateTime.class));
    }
    private static TaskHandoffOffer offer(ResultSet r) throws SQLException {
        return new TaskHandoffOffer(r.getLong(1), r.getString(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6),
                r.getLong(7), r.getString(8), r.getString(9), r.getObject(10, LocalDateTime.class), r.getObject(11, LocalDateTime.class));
    }
}
