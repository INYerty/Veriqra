package io.github.lz007001cn.veriqra.jdbc;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPoolTest {
    private ConnectionPool pool(int initial, int max, long ms, List<FakeJdbc> made) throws SQLException {
        return new ConnectionPool(initial, max, Duration.ofMillis(ms), () -> {
            FakeJdbc f = new FakeJdbc(); made.add(f); return f.connection;
        });
    }

    @Test void prewarmsAndReturnsWithoutClosingPhysicalConnection() throws Exception {
        List<FakeJdbc> made = new ArrayList<>();
        try (var pool = pool(2,2,1000,made)) {
            assertEquals(2, made.size());
            Connection first = pool.borrow(); first.close(); first.close();
            assertTrue(first.isClosed()); assertFalse(made.getFirst().closed);
            assertFalse(first.isValid(1));
            try (Connection second = pool.borrow()) { assertFalse(second.isClosed()); }
            assertEquals(2, made.size());
        }
        assertTrue(made.stream().allMatch(f -> f.closed));
    }

    @Test void maxCapacityTimesOutAndReturnedSlotCanBeReused() throws Exception {
        List<FakeJdbc> made = new ArrayList<>();
        try (var pool = pool(0,1,80,made)) {
            Connection c = pool.borrow();
            assertThrows(SQLTimeoutException.class, pool::borrow);
            assertEquals(1,made.size());
            c.close();
            try (Connection reused=pool.borrow()) { assertFalse(reused.isClosed()); }
            assertEquals(1,made.size());
        }
    }

    @Test void returnRollsBackAndResetsState() throws Exception {
        List<FakeJdbc> made = new ArrayList<>();
        try (var pool=pool(1,1,1000,made)) {
            try (Connection c=pool.borrow()) {
                c.setAutoCommit(false); c.setReadOnly(true); c.setCatalog("other");
                c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            }
            try (Connection c=pool.borrow()) {
                assertTrue(c.getAutoCommit()); assertFalse(c.isReadOnly()); assertEquals("test",c.getCatalog());
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());
            }
            assertEquals(1,made.getFirst().rollbacks); assertEquals(0,made.getFirst().commits);
        }
    }

    @Test void staleLeaseCannotOperateAfterReturn() throws Exception {
        try (var pool=pool(0,1,1000,new ArrayList<>())) {
            Connection old=pool.borrow(); old.close();
            assertFalse(old.isValid(1));
            try (Connection current=pool.borrow()) {
                assertThrows(SQLException.class,old::createStatement);
                old.close(); // Must not return the currently borrowed lease.
                assertThrows(SQLTimeoutException.class,pool::borrow);
                assertFalse(current.isClosed());
                assertThrows(SQLException.class,()->current.isValid(-1));
            }
        }
    }

    @Test void invalidIdleConnectionIsReplaced() throws Exception {
        List<FakeJdbc> made=new ArrayList<>();
        try (var pool=pool(1,1,1000,made)) {
            made.getFirst().valid=false;
            try(Connection c=pool.borrow()) { assertFalse(c.isClosed()); }
            assertEquals(2,made.size()); assertTrue(made.getFirst().closed);
        }
    }

    @Test void resetFailureDiscardsPhysicalConnection() throws Exception {
        List<FakeJdbc> made=new ArrayList<>();
        try(var pool=pool(1,1,1000,made)) {
            Connection c=pool.borrow(); c.setAutoCommit(false); made.getFirst().rollbackFails=true;
            assertThrows(SQLException.class,c::close); assertTrue(made.getFirst().closed);
            try(Connection replacement=pool.borrow()) { assertFalse(replacement.isClosed()); }
            assertEquals(2,made.size());
        }
    }

    @Test void creationFailureReleasesReservedSlot() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try(var pool=new ConnectionPool(0,1,Duration.ofSeconds(1),()-> {
            if(calls.getAndIncrement()==0) throw new SQLException("offline");
            return new FakeJdbc().connection;
        })) {
            assertThrows(SQLException.class,pool::borrow);
            try(Connection c=pool.borrow()) { assertFalse(c.isClosed()); }
        }
    }

    @Test void partialInitializationClosesAlreadyCreatedConnections() {
        FakeJdbc first=new FakeJdbc(); AtomicInteger count=new AtomicInteger();
        assertThrows(SQLException.class,()->new ConnectionPool(2,2,Duration.ofSeconds(1),()-> {
            if(count.getAndIncrement()==1) throw new SQLException("offline");
            return first.connection;
        }));
        assertTrue(first.closed);
    }

    @Test void closingPoolInvalidatesOutstandingLeasesAndIsIdempotent() throws Exception {
        List<FakeJdbc> made=new ArrayList<>(); var pool=pool(1,1,1000,made);
        Connection c=pool.borrow(); pool.close(); pool.close();
        assertTrue(made.getFirst().closed); assertTrue(c.isClosed());
        assertFalse(c.isValid(1));
        assertThrows(SQLException.class,c::commit); assertThrows(SQLException.class,pool::borrow); c.close();
    }

    @Test void closeWakesCapacityWaiter() throws Exception {
        try(var executor=Executors.newSingleThreadExecutor(); var pool=pool(1,1,30000,new ArrayList<>())) {
            Connection held=pool.borrow(); CountDownLatch started=new CountDownLatch(1);
            Future<SQLException> result=executor.submit(()-> { started.countDown(); return assertThrows(SQLException.class,pool::borrow); });
            assertTrue(started.await(1,TimeUnit.SECONDS)); pool.close();
            assertEquals("08003",result.get(1,TimeUnit.SECONDS).getSQLState()); held.close();
        }
    }

    @Test void concurrentBorrowersNeverExceedMaximumAndWakeOnReturn() throws Exception {
        List<FakeJdbc> made=Collections.synchronizedList(new ArrayList<>());
        try(var executor=Executors.newFixedThreadPool(8); var pool=pool(0,2,3000,made)) {
            CountDownLatch start=new CountDownLatch(1); AtomicInteger active=new AtomicInteger(); AtomicInteger peak=new AtomicInteger();
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(executor.submit(()-> {
                start.await();
                for(int j=0;j<15;j++) try(Connection c=pool.borrow()) {
                    int n=active.incrementAndGet(); peak.accumulateAndGet(n,Math::max);
                    try { assertTrue(c.isValid(1)); Thread.yield(); } finally { active.decrementAndGet(); }
                }
                return null;
            }));
            start.countDown(); for(Future<?> f:futures) f.get(5,TimeUnit.SECONDS);
            assertTrue(peak.get()<=2); assertTrue(made.size()<=2);
        }
    }

    @Test void closeDuringConnectionCreationDoesNotLeak() throws Exception {
        FakeJdbc physical=new FakeJdbc(); CountDownLatch creating=new CountDownLatch(1), finish=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor(); var pool=new ConnectionPool(0,1,Duration.ofSeconds(3),()-> {
            creating.countDown();
            try { if(!finish.await(2,TimeUnit.SECONDS)) throw new SQLException("test timeout"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
            return physical.connection;
        })) {
            Future<?> result=executor.submit(()->assertThrows(SQLException.class,pool::borrow));
            assertTrue(creating.await(1,TimeUnit.SECONDS)); pool.close(); finish.countDown(); result.get(2,TimeUnit.SECONDS);
            assertTrue(physical.closed);
        }
    }

    @Test void leakedStatementsAreClosedAndCannotExposePhysicalConnection() throws Exception {
        List<FakeJdbc> made=new ArrayList<>();
        try(var pool=pool(1,1,1000,made)) {
            Connection c=pool.borrow(); Statement s=c.createStatement();
            assertSame(c,s.getConnection()); assertSame(c,c.unwrap(Connection.class));
            assertThrows(SQLException.class,()->c.unwrap(FakeJdbc.class));
            c.close(); assertTrue(s.isClosed()); assertEquals(0,made.getFirst().openStatements.get());
            assertThrows(SQLException.class,()->s.execute("SELECT 1"));
        }
    }

    @Test void interruptionPreservesInterruptFlag() throws Exception {
        try(var pool=pool(1,1,30000,new ArrayList<>()); Connection held=pool.borrow()) {
            AtomicReference<Throwable> failure=new AtomicReference<>(); AtomicBoolean interrupted=new AtomicBoolean();
            Thread worker=new Thread(()-> {
                Thread.currentThread().interrupt();
                try { pool.borrow(); } catch(Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            worker.start(); worker.join(2000);
            assertFalse(worker.isAlive()); assertInstanceOf(SQLException.class,failure.get()); assertTrue(interrupted.get());
        }
    }
    @Test void closingPhysicalConnectionStillConsumesCapacity() throws Exception {
        List<FakeJdbc> made=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch closing=new CountDownLatch(1), finishClose=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor(); var pool=pool(1,1,120,made)) {
            FakeJdbc first=made.getFirst(); first.valid=false;
            first.beforeClose=()-> {
                closing.countDown();
                try { if(!finishClose.await(3,TimeUnit.SECONDS)) throw new AssertionError("test cleanup timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            };
            Future<?> retiring=executor.submit(()-> {
                try(Connection c=pool.borrow()) { return null; } catch(SQLException expected) { return null; }
            });
            try {
                assertTrue(closing.await(1,TimeUnit.SECONDS));
                assertThrows(SQLTimeoutException.class,()-> { try(Connection c=pool.borrow()) { /* capacity is still occupied */ } });
                assertEquals(1,made.size());
            } finally { finishClose.countDown(); retiring.get(2,TimeUnit.SECONDS); }
        }
    }
}
