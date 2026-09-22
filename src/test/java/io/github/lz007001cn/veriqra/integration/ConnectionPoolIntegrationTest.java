package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.config.DatabaseConfig;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao;
import io.github.lz007001cn.veriqra.jdbc.ConnectionPool;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPoolIntegrationTest extends MysqlFixture {
    private ConnectionPool singlePool() throws SQLException {
        return new ConnectionPool(new DatabaseConfig(config.jdbcUrl(),config.username(),config.password(),1,1,Duration.ofMillis(300)));
    }
    private long connectionId(Connection c) throws SQLException {
        try(var s=c.prepareStatement("SELECT CONNECTION_ID()"); var r=s.executeQuery()) { r.next(); return r.getLong(1); }
    }
    @Test void closedPreparedStatementCannotReviveThroughDriverCache() throws Exception {
        var cachedConfig=new DatabaseConfig(config.jdbcUrl()+"?useServerPrepStmts=true&cachePrepStmts=true",
                config.username(),config.password(),1,1,Duration.ofSeconds(2));
        try(var cached=new ConnectionPool(cachedConfig); var c=cached.borrow()) {
            PreparedStatement old=c.prepareStatement("SELECT ?"); old.setInt(1,11); old.close();
            try(var current=c.prepareStatement("SELECT ?")) {
                current.setInt(1,22);
                assertTrue(old.isClosed(),"A closed logical statement must stay closed when the driver reuses its physical object");
                assertThrows(SQLException.class,old::executeQuery);
                old.close(); // Must not close current's cached physical statement.
                try(var r=current.executeQuery()) { r.next(); assertEquals(22,r.getInt(1)); }
            }
        }
    }
    @Test void schemaModeRestoresSelectedDatabaseOnReturn() throws Exception {
        var schemaConfig=new DatabaseConfig(config.jdbcUrl()+"?databaseTerm=SCHEMA",
                config.username(),config.password(),1,1,Duration.ofSeconds(2));
        try(var single=new ConnectionPool(schemaConfig)) {
            String initial;
            try(var c=single.borrow()) { initial=c.getSchema(); c.setSchema("information_schema"); }
            try(var c=single.borrow()) { assertEquals(initial,c.getSchema()); }
        }
    }
    @Test void physicalConnectionReusedAfterLogicalClose() throws Exception {
        try(var single=singlePool()) {
            long id; Connection old=single.borrow(); id=connectionId(old); old.close();
            try(var c=single.borrow()) { assertEquals(id,connectionId(c)); assertThrows(SQLException.class,old::createStatement); }
        }
    }
    @Test void poolCapacityTimeoutIsEnforcedOnRealDriver() throws Exception {
        try(var single=singlePool(); var held=single.borrow()) { assertThrows(SQLTimeoutException.class,single::borrow); }
    }
    @Test void abandonedTransactionRollsBackBeforeNextBorrower() throws Exception {
        try(var single=singlePool()) {
            try(var c=single.borrow()) { c.setAutoCommit(false); new JdbcUserDao(c).insert(user("abandoned")); }
            try(var c=single.borrow()) { assertTrue(c.getAutoCommit()); assertTrue(new JdbcUserDao(c).findByUsername("abandoned").isEmpty()); }
        }
    }
    @Test void actualSessionStateAndResourceHandlesReset() throws Exception {
        try(var single=singlePool()) {
            Connection old=single.borrow(); Statement leaked=old.createStatement(); ResultSet rs=leaked.executeQuery("SELECT 1");
            assertSame(old,rs.getStatement().getConnection()); assertSame(old,old.getMetaData().getConnection());
            old.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var s=old.createStatement()) { s.execute("SET SESSION time_zone = '+08:00'"); s.execute("SET SESSION sql_mode = ''"); }
            old.close(); assertTrue(leaked.isClosed()); assertTrue(rs.isClosed());
            try(var c=single.borrow(); var s=c.prepareStatement("SELECT @@session.time_zone,@@session.sql_mode"); var r=s.executeQuery()) {
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());
                r.next(); assertEquals("+00:00",r.getString(1)); assertTrue(r.getString(2).contains("STRICT_TRANS_TABLES"));
            }
        }
    }
    @Test void poolCloseActuallyDisconnectsMysqlSession() throws Exception {
        var single=singlePool(); Connection c=single.borrow(); long id=connectionId(c); single.close();
        assertTrue(c.isClosed()); assertThrows(SQLException.class,c::createStatement);
        try(var observer=pool.borrow(); var s=observer.prepareStatement("SELECT COUNT(*) FROM information_schema.processlist WHERE id=?")) {
            s.setLong(1,id); try(var r=s.executeQuery()) { r.next(); assertEquals(0,r.getInt(1)); }
        }
        c.close();
    }
}
