package io.github.lz007001cn.veriqra.service.support;

import io.github.lz007001cn.veriqra.jdbc.JdbcTransactionManager;
import java.util.Objects;

/** Adapter; JdbcTransactionManager remains the sole owner of commit, rollback and Connection close. */
public final class JdbcServiceTransaction implements ServiceTransaction {
    private final JdbcTransactionManager transactions;
    public JdbcServiceTransaction(JdbcTransactionManager transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }
    @Override public <T> T execute(Work<T> work) {
        Objects.requireNonNull(work);
        return transactions.inTransaction(work::execute);
    }
}
