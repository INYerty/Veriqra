package io.github.lz007001cn.veriqra.jdbc;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Owns a connection and one transaction; DAOs supplied with that connection never commit or close it. */
public final class JdbcTransactionManager {
    @FunctionalInterface public interface Work<T> { T execute(Connection connection) throws SQLException; }
    private final ConnectionPool pool;
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

    public JdbcTransactionManager(ConnectionPool pool) { this.pool = Objects.requireNonNull(pool); }

    /** No nested transactions/automatic retries. Pass the callback's connection to every participating DAO. */
    public <T> T inTransaction(Work<T> work) {
        if (active.get()) throw new IllegalStateException("Nested transactions are unsupported; reuse the existing connection");
        active.set(true);
        boolean committed = false;
        try (Connection c = pool.borrow()) {
            c.setAutoCommit(false);
            try {
                T result = work.execute(c);
                c.commit();
                committed = true;
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException e) {
            throw new DataAccessException(committed ? "Transaction committed; connection cleanup" : "JDBC transaction", e);
        } finally { active.remove(); }
    }
}
