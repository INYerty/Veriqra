package io.github.lz007001cn.veriqra.jdbc;

import io.github.lz007001cn.veriqra.config.DatabaseConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Small bounded JDBC pool. A borrowed connection is an exclusive, non-thread-safe lease. */
public final class ConnectionPool implements AutoCloseable {
    @FunctionalInterface interface ConnectionFactory { Connection open() throws SQLException; }
    private final ConnectionFactory factory;
    private final int maxSize;
    private final long timeoutNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Deque<Entry> idle = new ArrayDeque<>();
    private final Set<Entry> entries = new HashSet<>();
    private int size; // Includes slots reserved for connections being created outside the lock.
    private volatile boolean closed;

    public ConnectionPool(DatabaseConfig config) throws SQLException {
        this(config.initialPoolSize(), config.maxPoolSize(), config.acquireTimeout(), () -> {
            Properties p = new Properties();
            p.setProperty("user", config.username());
            p.setProperty("password", config.password());
            // Driver timeouts bound network operations separately from pool-capacity waiting.
            p.setProperty("connectTimeout", "3000");
            p.setProperty("socketTimeout", "10000");
            return DriverManager.getConnection(config.jdbcUrl(), p);
        });
    }

    ConnectionPool(int initialSize, int maxSize, Duration timeout, ConnectionFactory factory) throws SQLException {
        if (initialSize < 0 || maxSize < 1 || initialSize > maxSize || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Invalid pool bounds or timeout");
        }
        this.maxSize = maxSize;
        this.timeoutNanos = timeout.toNanos();
        this.factory = Objects.requireNonNull(factory);
        try {
            for (int i = 0; i < initialSize; i++) {
                Entry e = create(); entries.add(e); idle.add(e); size++;
            }
        } catch (SQLException | RuntimeException failure) {
            try { close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Waits at most acquireTimeout for capacity; slow JDBC I/O also has driver-level timeouts. */
    public Connection borrow() throws SQLException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            Entry candidate;
            try {
                if (!lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw timeout();
                try {
                    while (true) {
                        if (closed) throw new SQLException("Connection pool is closed", "08003");
                        if (deadline - System.nanoTime() <= 0) throw timeout();
                        candidate = idle.pollFirst();
                        if (candidate != null) break;
                        if (size < maxSize) { size++; break; }
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw timeout();
                        available.awaitNanos(remaining);
                    }
                } finally { lock.unlock(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for a connection", "HY008", e);
            }
            if (candidate == null) {
                try { candidate = create(); }
                catch (SQLException | RuntimeException e) {
                    lock.lock();
                    try { size--; available.signalAll(); } finally { lock.unlock(); }
                    throw e;
                }
                lock.lock();
                try {
                    if (closed) {
                        size--;
                        closeQuietly(candidate.connection);
                        throw new SQLException("Connection pool is closed", "08003");
                    }
                    entries.add(candidate);
                } finally { lock.unlock(); }
            }
            boolean valid;
            try { valid = !candidate.connection.isClosed() && candidate.connection.isValid(1); }
            catch (SQLException e) { valid = false; }
            if (!valid) { discard(candidate); continue; }
            if (deadline - System.nanoTime() <= 0) { discard(candidate); throw timeout(); }
            lock.lock();
            try {
                if (closed) throw new SQLException("Connection pool is closed", "08003");
                return new Lease(candidate).proxy;
            } finally { lock.unlock(); }
        }
    }

    private static SQLTimeoutException timeout() { return new SQLTimeoutException("Connection acquisition timed out", "HYT00"); }

    private Entry create() throws SQLException {
        Connection c = factory.open();
        try {
            if (!c.getAutoCommit()) c.rollback();
            c.setAutoCommit(true);
            c.setReadOnly(false);
            initializeSession(c);
            return new Entry(c, c.getCatalog(), c.getSchema(), c.getTransactionIsolation());
        } catch (SQLException | RuntimeException e) {
            try { c.close(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static void initializeSession(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET SESSION time_zone = '+00:00'");
            s.execute("SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'");
        }
    }

    private void release(Entry e) throws SQLException {
        if (closed) { discard(e); return; }
        try {
            Connection c = e.connection;
            if (c.isClosed()) { discard(e); return; }
            if (!c.getAutoCommit()) c.rollback(); // Never commit abandoned work on return.
            c.setReadOnly(false);
            c.setTransactionIsolation(e.isolation);
            c.setCatalog(e.catalog);
            c.setSchema(e.schema);
            c.setAutoCommit(true);
            c.clearWarnings();
            initializeSession(c);
        } catch (SQLException e1) { discard(e); throw e1; }
        lock.lock();
        try {
            if (!closed) { idle.addLast(e); available.signalAll(); }
        } finally { lock.unlock(); }
    }

    private void discard(Entry e) {
        lock.lock();
        try {
            idle.remove(e);
        } finally { lock.unlock(); }
        // Keep the reserved slot until physical close finishes. Otherwise a concurrent
        // borrower can open a replacement while the retiring connection is still open.
        try { closeQuietly(e.connection); }
        finally {
            lock.lock();
            try {
                if (entries.remove(e)) size--;
                available.signalAll();
            } finally { lock.unlock(); }
        }
    }

    private static void closeQuietly(Connection c) {
        try { c.close(); }
        catch (SQLException e) {
            System.getLogger(ConnectionPool.class.getName()).log(System.Logger.Level.WARNING,
                    "Physical connection cleanup failed; connection removed from pool");
        }
    }

    /** Idempotent shutdown. Outstanding leases are invalidated and physical connections closed. */
    @Override public void close() throws SQLException {
        List<Entry> snapshot;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<>(entries);
            size -= entries.size(); entries.clear(); idle.clear(); available.signalAll();
        } finally { lock.unlock(); }
        SQLException failure = null;
        for (Entry e : snapshot) {
            try { e.connection.close(); }
            catch (SQLException x) { if (failure == null) failure = x; else failure.addSuppressed(x); }
        }
        if (failure != null) throw failure;
    }

    private record Entry(Connection connection, String catalog, String schema, int isolation) { }

    private final class Lease {
        private final Entry entry;
        private final Set<Statement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean returned;
        private final Connection proxy;

        Lease(Entry entry) {
            this.entry = entry;
            proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this::invoke);
        }

        private void checkOpen() throws SQLException {
            if (returned || closed || entry.connection.isClosed()) throw new SQLException("Connection lease is closed", "08003");
        }

        private synchronized Object invoke(Object self, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) return objectMethod(self, name, args);
            if (name.equals("close")) {
                if (!returned) {
                    returned = true;
                    SQLException failure = null;
                    for (Statement s : statements) {
                        try { s.close(); } catch (SQLException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
                    }
                    statements.clear();
                    if (failure != null) { discard(entry); throw failure; }
                    release(entry);
                }
                return null;
            }
            if (name.equals("isClosed")) return returned || closed || entry.connection.isClosed();
            if (name.equals("isValid")) {
                if ((int) args[0] < 0) throw new SQLException("Validation timeout must be nonnegative", "HY092");
                if (returned || closed || entry.connection.isClosed()) return false;
            }
            checkOpen();
            if (name.equals("unwrap") || name.equals("isWrapperFor")) return wrapperMethod(self, name, args);
            if (name.equals("abort")) throw new SQLFeatureNotSupportedException("Use close() to return the lease");
            Object result = call(entry.connection, method, args);
            if (result instanceof Statement s) {
                statements.add(s);
                Class<?> type = s instanceof CallableStatement ? CallableStatement.class
                        : s instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return wrapChild(s, type, null);
            }
            if (result instanceof DatabaseMetaData m) return wrapChild(m, DatabaseMetaData.class, null);
            return result;
        }

        private Object wrapChild(Object raw, Class<?> type, Object parentStatement) {
            // A driver may cache/reuse the physical statement object after close().
            // Logical closure belongs to this handle, not to that reusable object.
            boolean[] handleClosed = {false};
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
                synchronized (this) {
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class) return objectMethod(self, name, args);
                    if (name.equals("close")) {
                        if (handleClosed[0] || returned || closed) return null;
                        handleClosed[0] = true;
                        Object result = call(raw, method, args);
                        if (raw instanceof Statement) statements.remove(raw);
                        return result;
                    }
                    if (name.equals("isClosed") && (handleClosed[0] || returned || closed)) return true;
                    checkOpen();
                    if (handleClosed[0]) throw new SQLException("JDBC resource handle is closed", "HY010");
                    if (name.equals("unwrap") || name.equals("isWrapperFor")) return wrapperMethod(self, name, args);
                    if (name.equals("getConnection")) return proxy;
                    if (name.equals("getStatement")) return parentStatement;
                    Object result = call(raw, method, args);
                    if (result instanceof ResultSet rs) return wrapChild(rs, ResultSet.class, raw instanceof Statement ? self : null);
                    return result;
                }
            });
        }
    }

    private static Object objectMethod(Object self, String name, Object[] args) {
        return switch (name) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            default -> "Veriqra pooled JDBC handle";
        };
    }

    private static Object wrapperMethod(Object self, String name, Object[] args) throws SQLException {
        boolean supported = ((Class<?>) args[0]).isInstance(self);
        if (name.equals("isWrapperFor")) return supported;
        if (supported) return self;
        throw new SQLException("Physical/vendor unwrapping is not supported");
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
