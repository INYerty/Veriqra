package io.github.lz007001cn.veriqra.jdbc;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Narrow JDBC test double: unsupported operations fail instead of fabricating driver behavior. */
final class FakeJdbc {
    boolean closed;
    boolean valid = true;
    boolean autoCommit = true;
    boolean readOnly;
    boolean rollbackFails;
    boolean commitFails;
    boolean autoCommitResetFails;
    int isolation = Connection.TRANSACTION_REPEATABLE_READ;
    String catalog = "test";
    String schema = "test_schema";
    int commits;
    int rollbacks;
    Runnable beforeClose = () -> { };
    final AtomicInteger openStatements = new AtomicInteger();
    final Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (self, m, args) -> {
                switch (m.getName()) {
                    case "close": beforeClose.run(); closed = true; return null;
                    case "isClosed": return closed;
                    case "isValid": return valid && !closed;
                    case "getAutoCommit": return autoCommit;
                    case "setAutoCommit":
                        if ((boolean) args[0] && autoCommitResetFails) throw new SQLException("simulated reset failure", "08006");
                        autoCommit = (boolean) args[0]; return null;
                    case "isReadOnly": return readOnly;
                    case "setReadOnly": readOnly = (boolean) args[0]; return null;
                    case "getCatalog": return catalog;
                    case "setCatalog": catalog = (String) args[0]; return null;
                    case "getSchema": return schema;
                    case "setSchema": schema = (String) args[0]; return null;
                    case "getTransactionIsolation": return isolation;
                    case "setTransactionIsolation": isolation = (int) args[0]; return null;
                    case "clearWarnings": return null;
                    case "commit":
                        if (commitFails) throw new SQLException("simulated commit failure", "08006");
                        commits++; return null;
                    case "rollback":
                        if (rollbackFails) throw new SQLException("simulated rollback failure", "08006");
                        rollbacks++; return null;
                    case "createStatement": return statement();
                    case "equals": return self == args[0];
                    case "hashCode": return System.identityHashCode(self);
                    default: throw new SQLFeatureNotSupportedException(m.getName());
                }
            });

    private Statement statement() {
        openStatements.incrementAndGet();
        boolean[] done = {false};
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (self,m,args) -> {
            return switch (m.getName()) {
                case "execute" -> false;
                case "close" -> { if (!done[0]) openStatements.decrementAndGet(); done[0] = true; yield null; }
                case "isClosed" -> done[0];
                case "getConnection" -> connection;
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                default -> throw new SQLFeatureNotSupportedException(m.getName());
            };
        });
    }
}
