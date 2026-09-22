package io.github.lz007001cn.veriqra.jdbc;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import org.junit.jupiter.api.*;
import java.sql.SQLException;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class JdbcTransactionManagerTest {
    private FakeJdbc physical;
    private ConnectionPool pool;
    private JdbcTransactionManager tx;
    @BeforeEach void setUp() throws SQLException {
        physical=new FakeJdbc(); pool=new ConnectionPool(1,1,Duration.ofSeconds(1),()->physical.connection);
        tx=new JdbcTransactionManager(pool);
    }
    @AfterEach void tearDown() throws SQLException { pool.close(); }
    @Test void successCommitsAndReturnsConnection() throws Exception {
        assertEquals("done",tx.inTransaction(c->{ assertFalse(c.getAutoCommit()); return "done"; }));
        assertEquals(1,physical.commits);
        try(var c=pool.borrow()) { assertTrue(c.getAutoCommit()); }
    }
    @Test void runtimeFailureRollsBackAndDoesNotCommit() {
        var original=new IllegalArgumentException("test");
        assertSame(original,assertThrows(IllegalArgumentException.class,()->tx.inTransaction(c->{throw original;})));
        assertTrue(physical.rollbacks>=1); assertEquals(0,physical.commits);
    }
    @Test void sqlFailureIsTranslatedAndCausePreserved() {
        var sql=new SQLException("test","23000",1062);
        var error=assertThrows(DataAccessException.class,()->tx.inTransaction(c->{throw sql;}));
        assertSame(sql,error.getCause()); assertEquals(1062,error.getVendorCode());
    }
    @Test void errorAlsoRollsBack() {
        assertThrows(AssertionError.class,()->tx.inTransaction(c->{throw new AssertionError("test");}));
        assertEquals(0,physical.commits); assertTrue(physical.rollbacks>=1);
    }
    @Test void rollbackFailureDoesNotReplacePrimaryFailure() {
        physical.rollbackFails=true; var original=new IllegalStateException("primary");
        assertSame(original,assertThrows(IllegalStateException.class,()->tx.inTransaction(c->{throw original;})));
        assertTrue(original.getSuppressed().length>=1); assertTrue(physical.closed);
    }
    @Test void commitFailureIsNotRetried() {
        physical.commitFails=true;
        var error=assertThrows(DataAccessException.class,()->tx.inTransaction(c->"done"));
        assertEquals("08006",error.getSqlState()); assertEquals(0,physical.commits);
    }
    @Test void nestedTransactionRejectedAndGuardClearedAfterFailure() {
        assertThrows(IllegalStateException.class,()->tx.inTransaction(c->tx.inTransaction(inner->null)));
        assertEquals("ok",tx.inTransaction(c->"ok")); assertEquals(1,physical.commits);
    }
    @Test void cleanupFailureAfterCommitExplicitlyReportsCommittedOutcome() {
        var error=assertThrows(DataAccessException.class,()->tx.inTransaction(c->{
            physical.autoCommitResetFails=true; return "done";
        }));
        assertEquals(1,physical.commits); assertTrue(physical.closed);
        assertTrue(error.getMessage().contains("Transaction committed"));
    }
    @Test void cleanupFailureDoesNotHideBusinessException() {
        var original=new IllegalArgumentException("business failure");
        var error=assertThrows(IllegalArgumentException.class,()->tx.inTransaction(c->{
            physical.autoCommitResetFails=true; throw original;
        }));
        assertSame(original,error); assertEquals(0,physical.commits);
        assertEquals(1,error.getSuppressed().length); assertInstanceOf(SQLException.class,error.getSuppressed()[0]);
    }
    @Test void accidentalConnectionCloseCannotCommitPartialWork() throws Exception {
        assertThrows(DataAccessException.class,()->tx.inTransaction(c->{ c.close(); return null; }));
        assertEquals(0,physical.commits); assertTrue(physical.rollbacks>=1);
        try(var c=pool.borrow()) { assertTrue(c.getAutoCommit()); }
    }
}
