package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.exception.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public final class JdbcCreditDao implements CreditDao {
    private final Connection connection;
    public JdbcCreditDao(Connection connection) { this.connection=Objects.requireNonNull(connection); }

    @Override public void createAccount(long userId) {
        try(var s=connection.prepareStatement("INSERT INTO credit_accounts(user_id,balance) VALUES(?,0)")) {
            s.setLong(1,userId); s.executeUpdate();
        } catch(SQLException e) { throw new DataAccessException("Create credit account",e); }
    }

    @Override public Optional<LockedAccount> lockAccount(long userId) {
        try {
            if(connection.getAutoCommit()) throw new SQLException("Credit lock requires transaction");
            try(var s=connection.prepareStatement("SELECT user_id,balance,lock_version FROM credit_accounts WHERE user_id=? FOR UPDATE")) {
                s.setLong(1,userId);
                try(var rs=s.executeQuery()) { return rs.next()?Optional.of(new LockedAccount(rs.getLong(1),rs.getLong(2),rs.getInt(3))):Optional.empty(); }
            }
        } catch(SQLException e) { throw new DataAccessException("Lock credit account",e); }
    }

    @Override public void updateBalance(LockedAccount before,long after) {
        try(var s=connection.prepareStatement("UPDATE credit_accounts SET balance=?,updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE user_id=? AND lock_version=?")) {
            s.setLong(1,after);s.setLong(2,before.userId());s.setInt(3,before.version());
            if(s.executeUpdate()!=1) throw new OptimisticLockException();
        } catch(SQLException e) { throw new DataAccessException("Update credit balance",e); }
    }

    @Override public CreditEntry append(long userId,long amount,String type,long actorId,String reason,String batchId) {
        try(var s=connection.prepareStatement("INSERT INTO credit_transactions(user_id,amount,type,actor_user_id,reason,batch_id) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1,userId);s.setLong(2,amount);s.setString(3,type);s.setLong(4,actorId);s.setString(5,reason);s.setString(6,batchId);
            if(s.executeUpdate()!=1) throw new SQLException("Credit ledger insert failed");
            long id;try(var keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing ledger ID");id=keys.getLong(1);}
            try(var find=connection.prepareStatement("SELECT id,user_id,amount,type,actor_user_id,reason,batch_id,created_at FROM credit_transactions WHERE id=?")) {
                find.setLong(1,id);try(var rs=find.executeQuery()){if(!rs.next())throw new SQLException("Missing inserted ledger row");return map(rs);}
            }
        } catch(SQLException e) { throw new DataAccessException("Append credit ledger",e); }
    }

    @Override public Page<CreditAccountView> listAccounts(int page,int size) {
        try(var count=connection.prepareStatement("SELECT COUNT(*) FROM credit_accounts");
            var s=connection.prepareStatement("SELECT a.user_id,u.username,u.display_name,u.status,a.balance,a.updated_at,a.lock_version FROM credit_accounts a JOIN users u ON u.id=a.user_id ORDER BY a.user_id DESC LIMIT ? OFFSET ?")) {
            long total;try(var rs=count.executeQuery()){rs.next();total=rs.getLong(1);}
            s.setInt(1,size);s.setInt(2,Math.multiplyExact(page-1,size));
            List<CreditAccountView> rows=new ArrayList<>();try(var rs=s.executeQuery()){while(rs.next())rows.add(mapAccount(rs));}
            return new Page<>(rows,total,page,size);
        } catch(SQLException e) { throw new DataAccessException("List credit accounts",e); }
    }

    @Override public Optional<CreditAccountView> findAccount(long userId) {
        try(var s=connection.prepareStatement("SELECT a.user_id,u.username,u.display_name,u.status,a.balance,a.updated_at,a.lock_version FROM credit_accounts a JOIN users u ON u.id=a.user_id WHERE a.user_id=?")) {
            s.setLong(1,userId);try(var rs=s.executeQuery()){return rs.next()?Optional.of(mapAccount(rs)):Optional.empty();}
        } catch(SQLException e) { throw new DataAccessException("Find credit account",e); }
    }

    @Override public Page<CreditEntry> listEntries(Long userId,String username,String type,Long actorId,String batchId,
                                                   LocalDateTime from,LocalDateTime to,int page,int size) {
        List<Object> parameters=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(userId!=null){where.append(" AND t.user_id=?");parameters.add(userId);}
        if(username!=null){where.append(" AND u.username=?");parameters.add(username);}
        if(type!=null){where.append(" AND t.type=?");parameters.add(type);}
        if(actorId!=null){where.append(" AND t.actor_user_id=?");parameters.add(actorId);}
        if(batchId!=null){where.append(" AND t.batch_id=?");parameters.add(batchId);}
        if(from!=null){where.append(" AND t.created_at>=?");parameters.add(from);}
        if(to!=null){where.append(" AND t.created_at<?");parameters.add(to);}
        String source="credit_transactions t JOIN users u ON u.id=t.user_id";
        try(var count=connection.prepareStatement("SELECT COUNT(*) FROM "+source+where);
            var s=connection.prepareStatement("SELECT t.id,t.user_id,t.amount,t.type,t.actor_user_id,t.reason,t.batch_id,t.created_at FROM "+source+where+" ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?")) {
            bind(count,parameters);bind(s,parameters);s.setInt(parameters.size()+1,size);s.setInt(parameters.size()+2,Math.multiplyExact(page-1,size));
            long total;try(var rs=count.executeQuery()){rs.next();total=rs.getLong(1);}
            List<CreditEntry> rows=new ArrayList<>();try(var rs=s.executeQuery()){while(rs.next())rows.add(map(rs));}
            return new Page<>(rows,total,page,size);
        } catch(SQLException e) { throw new DataAccessException("List credit ledger",e); }
    }

    @Override public CreditSummary summary() {
        try(var s=connection.prepareStatement("SELECT (SELECT COALESCE(SUM(balance),0) FROM credit_accounts),"
                +"(SELECT COALESCE(SUM(amount),0) FROM credit_transactions WHERE type='GRANT'),"
                +"(SELECT COALESCE(SUM(amount),0) FROM credit_transactions WHERE type='RECLAIM'),"
                +"(SELECT COUNT(*) FROM credit_accounts WHERE balance>0)" );var rs=s.executeQuery()) {
            rs.next();return new CreditSummary(rs.getBigDecimal(1).toBigIntegerExact(),
                    rs.getBigDecimal(2).toBigIntegerExact(),rs.getBigDecimal(3).toBigIntegerExact(),rs.getLong(4));
        } catch(SQLException e) { throw new DataAccessException("Credit summary",e); }
    }

    private static void bind(PreparedStatement s,List<Object> values) throws SQLException {
        for(int i=0;i<values.size();i++)s.setObject(i+1,values.get(i));
    }
    private static CreditAccountView mapAccount(ResultSet rs)throws SQLException {
        return new CreditAccountView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),
                rs.getObject(6,LocalDateTime.class),rs.getInt(7));
    }
    private static CreditEntry map(ResultSet rs)throws SQLException {
        return new CreditEntry(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getLong(5),
                rs.getString(6),rs.getString(7),rs.getObject(8,LocalDateTime.class));
    }
}
