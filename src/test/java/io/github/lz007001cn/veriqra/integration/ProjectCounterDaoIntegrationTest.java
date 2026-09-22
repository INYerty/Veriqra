package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCounterDaoIntegrationTest extends AssetFixture {
    @Test void initializeFourIndependentTypesAndAllocateWithinOuterTransaction() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcProjectCounterDao(c);
            for (var type : CounterEntityType.values()) dao.insert(new ProjectCounter(p.projectId(), type, 1L));
            assertEquals(4, dao.listByProject(p.projectId()).size());
            assertEquals(1L, dao.allocateNext(p.projectId(), CounterEntityType.REQ));
            assertEquals(2L, dao.allocateNext(p.projectId(), CounterEntityType.REQ));
            assertEquals(1L, dao.allocateNext(p.projectId(), CounterEntityType.TC));
            assertEquals(3L, dao.find(p.projectId(), CounterEntityType.REQ).orElseThrow().nextValue());
            assertEquals(1L, dao.find(p.projectId(), CounterEntityType.PLAN).orElseThrow().nextValue());
            assertEquals(1L, dao.find(p.projectId(), CounterEntityType.BUG).orElseThrow().nextValue());
            assertFalse(c.getAutoCommit()); assertFalse(c.isClosed()); return null;
        });
    }

    @Test void absentCounterAndAutocommitFailWithoutAllocation() throws Exception {
        var p = tx.inTransaction(this::parents);
        try (var c = pool.borrow()) {
            var dao = new JdbcProjectCounterDao(c);
            assertEquals("25000", assertThrows(DataAccessException.class, () -> dao.allocateNext(p.projectId(), CounterEntityType.REQ)).getSqlState());
        }
        tx.inTransaction(c -> {
            var dao = new JdbcProjectCounterDao(c);
            assertEquals("02000", assertThrows(DataAccessException.class, () -> dao.allocateNext(p.projectId(), CounterEntityType.REQ)).getSqlState());
            assertTrue(dao.listByProject(p.projectId()).isEmpty()); return null;
        });
    }

    @Test void primaryForeignCheckAndOverflowBoundaries() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcProjectCounterDao(c);
            var counter = new ProjectCounter(p.projectId(), CounterEntityType.REQ, Long.MAX_VALUE - 1);
            dao.insert(counter);
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(counter)).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new ProjectCounter(-1L, CounterEntityType.TC, 1L))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new ProjectCounter(p.projectId(), CounterEntityType.TC, 0L))).getVendorCode());
            assertEquals(Long.MAX_VALUE - 1, dao.allocateNext(p.projectId(), CounterEntityType.REQ));
            assertEquals("22003", assertThrows(DataAccessException.class, () -> dao.allocateNext(p.projectId(), CounterEntityType.REQ)).getSqlState());
            assertEquals(Long.MAX_VALUE, dao.find(p.projectId(), CounterEntityType.REQ).orElseThrow().nextValue()); return null;
        });
    }

    @Test void concurrentTransactionsAllocateDistinctKeysAndCommitMatchingAssets() throws Exception {
        var p = tx.inTransaction(c -> {
            var parents = parents(c);
            new JdbcProjectCounterDao(c).insert(new ProjectCounter(parents.projectId(), CounterEntityType.REQ, 1L)); return parents;
        });
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) futures.add(workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
                List<Long> keys = new ArrayList<>();
                for (int i = 0; i < 6; i++) keys.add(tx.inTransaction(c -> {
                    long key = new JdbcProjectCounterDao(c).allocateNext(p.projectId(), CounterEntityType.REQ);
                    new JdbcRequirementDao(c).insert(requirement(p, key)); return key;
                }));
                return keys;
            }));
            start.countDown();
            List<Long> allocated = new ArrayList<>();
            for (var future : futures) allocated.addAll(future.get(20, TimeUnit.SECONDS));
            assertEquals(LongStream.rangeClosed(1, 24).boxed().toList(), allocated.stream().sorted().toList());
        }
        tx.inTransaction(c -> {
            assertEquals(25L, new JdbcProjectCounterDao(c).find(p.projectId(), CounterEntityType.REQ).orElseThrow().nextValue());
            assertEquals(LongStream.rangeClosed(1, 24).boxed().toList(), new JdbcRequirementDao(c).listByProject(p.projectId()).stream().map(Requirement::keyNo).toList());
            return null;
        });
    }

    @Test void allocationAndAssetInsertRollbackTogetherOnLaterDatabaseFailure() {
        var p = tx.inTransaction(c -> {
            var parents = parents(c);
            new JdbcProjectCounterDao(c).insert(new ProjectCounter(parents.projectId(), CounterEntityType.REQ, 1L)); return parents;
        });
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            long key = new JdbcProjectCounterDao(c).allocateNext(p.projectId(), CounterEntityType.REQ);
            var dao = new JdbcRequirementDao(c); dao.insert(requirement(p, key));
            dao.insert(requirement(p, key)); return null;
        }));
        tx.inTransaction(c -> {
            var dao = new JdbcProjectCounterDao(c);
            assertEquals(1L, dao.find(p.projectId(), CounterEntityType.REQ).orElseThrow().nextValue());
            assertTrue(new JdbcRequirementDao(c).listByProject(p.projectId()).isEmpty());
            assertEquals(1L, dao.allocateNext(p.projectId(), CounterEntityType.REQ));
            new JdbcRequirementDao(c).insert(requirement(p, 1)); return null;
        });
    }

    @Test void lockingReadUsesCurrentValueEvenAfterEarlierSnapshotRead() throws Exception {
        var p = tx.inTransaction(c -> {
            var parents = parents(c);
            new JdbcProjectCounterDao(c).insert(new ProjectCounter(parents.projectId(), CounterEntityType.REQ, 1L)); return parents;
        });
        try (var oldSnapshot = pool.borrow()) {
            oldSnapshot.setAutoCommit(false);
            var dao = new JdbcProjectCounterDao(oldSnapshot);
            assertEquals(1L, dao.find(p.projectId(), CounterEntityType.REQ).orElseThrow().nextValue());
            Long committed = tx.inTransaction(c -> new JdbcProjectCounterDao(c).allocateNext(p.projectId(), CounterEntityType.REQ));
            assertEquals(1L, committed);
            assertEquals(2L, dao.allocateNext(p.projectId(), CounterEntityType.REQ));
            oldSnapshot.rollback();
        }
        tx.inTransaction(c -> {
            assertEquals(2L, new JdbcProjectCounterDao(c).find(p.projectId(), CounterEntityType.REQ).orElseThrow().nextValue()); return null;
        });
    }
}
