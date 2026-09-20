package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class TestAttemptDaoIntegrationTest extends ExecutionFixture {
    @Test void failThenPassPreservesBothFactsAndLatestUsesSequenceNotTimeOrId() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestAttemptDao(c);
            assertTrue(dao.findLatestByRunCase(e.runCase().id()).isEmpty());
            // Deliberately insert a higher sequence first, with an older execution time.
            var passInput = attempt(e, 2, TestAttemptStatus.PASS);
            var pass = dao.insert(new TestAttempt(null, passInput.testRunCaseId(), 2, passInput.status(), passInput.executedBy(), null, null,
                    passInput.executedAt().minusDays(1), null, null, null, null, passInput.submissionKey()));
            var fail = dao.insert(attempt(e, 1, TestAttemptStatus.FAIL));
            assertEquals(List.of(fail, pass), dao.listByRunCase(e.runCase().id()));
            assertEquals(pass, dao.findLatestByRunCase(e.runCase().id()).orElseThrow());
            assertEquals(fail, dao.findById(fail.id()).orElseThrow()); assertNotNull(fail.failureMessage());
            assertEquals(123456000, fail.executedAt().getNano()); assertEquals(123L, fail.durationMs());
            assertNull(pass.durationMs()); assertNull(pass.comment()); assertNull(pass.importId()); assertNull(pass.automationMappingId());
            assertNotNull(pass.recordedAt()); assertTrue(dao.findById(-1L).isEmpty());
            var blocked = dao.insert(attempt(e, 3, TestAttemptStatus.BLOCKED));
            var skipped = dao.insert(attempt(e, 4, TestAttemptStatus.SKIPPED));
            assertEquals(List.of(fail, pass, blocked, skipped), dao.listByRunCase(e.runCase().id()));
            return null;
        });
    }

    @Test void submissionUuidCanonicalBytesAndBothUniqueKeys() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestAttemptDao(c);
            var input = attempt(e, 1, TestAttemptStatus.FAIL); var saved = dao.insert(input);
            assertEquals(input.submissionKey(), saved.submissionKey());
            assertEquals(saved, dao.findBySubmissionKey(input.submissionKey()).orElseThrow());
            assertEquals(saved, dao.findBySubmissionKeyForUpdate(input.submissionKey()).orElseThrow());
            assertTrue(dao.findBySubmissionKey(UUID.randomUUID()).isEmpty());
            try (var s = c.prepareStatement("SELECT BIN_TO_UUID(submission_key, 0) FROM test_attempts WHERE id=?")) {
                s.setLong(1, saved.id()); try (var r = s.executeQuery()) { assertTrue(r.next()); assertEquals(input.submissionKey().toString(), r.getString(1)); }
            }
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(attempt(e, 1, TestAttemptStatus.PASS))).getVendorCode());
            var duplicateKey = new TestAttempt(null, saved.testRunCaseId(), 2, saved.status(), saved.executedBy(), null, null,
                    null, null, null, null, null, saved.submissionKey());
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(duplicateKey)).getVendorCode());
            assertEquals(List.of(saved), dao.listByRunCase(e.runCase().id())); return null;
        });
    }

    @Test void foreignKeysSourceShapeFailureShapeAndPositiveNumber() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestAttemptDao(c);
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(attempt(e, 0, TestAttemptStatus.PASS))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new TestAttempt(null, -1L, 1, TestAttemptStatus.PASS,
                    e.parents().userId(), null, null, null, null, null, null, null, UUID.randomUUID()))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new TestAttempt(null, e.runCase().id(), 1, TestAttemptStatus.PASS,
                    -1L, null, null, null, null, null, null, null, UUID.randomUUID()))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestAttempt(null, e.runCase().id(), 1, TestAttemptStatus.PASS,
                    null, null, null, null, null, null, null, null, UUID.randomUUID()))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestAttempt(null, e.runCase().id(), 1, TestAttemptStatus.PASS,
                    e.parents().userId(), null, null, null, null, null, null, "failure", UUID.randomUUID()))).getVendorCode());
            var saved = dao.insert(attempt(e, 1, TestAttemptStatus.PASS));
            try (var s = c.prepareStatement("UPDATE test_attempts SET status=? WHERE id=?")) {
                s.setString(1, "NOT_RUN"); s.setLong(2, saved.id());
                assertEquals(3819, assertThrows(java.sql.SQLException.class, s::executeUpdate).getErrorCode());
            }
            return null;
        });
    }

    @Test void unsignedValuesDoNotSilentlyOverflowSelectedJavaTypes() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestAttemptDao(c);
            var saved = dao.insert(new TestAttempt(null, e.runCase().id(), Integer.MAX_VALUE, TestAttemptStatus.PASS,
                    e.parents().userId(), null, null, null, null, Long.MAX_VALUE, null, null, UUID.randomUUID()));
            assertEquals(Integer.MAX_VALUE, saved.attemptNo()); assertEquals(Long.MAX_VALUE, saved.durationMs());
            // Boundary probes use raw SQL only in the isolated fixture, not an Attempt mutation API.
            try (var s = c.prepareStatement("UPDATE test_attempts SET attempt_no=? WHERE id=?")) {
                s.setLong(1, 2147483648L); s.setLong(2, saved.id()); s.executeUpdate();
                assertEquals("22003", assertThrows(DataAccessException.class, () -> dao.findById(saved.id())).getSqlState());
                s.setInt(1, 1); s.executeUpdate();
            }
            try (var s = c.prepareStatement("UPDATE test_attempts SET duration_ms=? WHERE id=?")) {
                s.setBigDecimal(1, new java.math.BigDecimal("9223372036854775808")); s.setLong(2, saved.id()); s.executeUpdate();
                assertEquals("22003", assertThrows(DataAccessException.class, () -> dao.findById(saved.id())).getSqlState());
            }
            return null;
        });
    }

    @Test void failedOuterTransactionLeavesNoNewAttempt() {
        var e = tx.inTransaction(this::execution);
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            var dao = new JdbcTestAttemptDao(c); dao.insert(attempt(e, 1, TestAttemptStatus.FAIL));
            dao.insert(attempt(e, 1, TestAttemptStatus.PASS)); return null;
        }));
        tx.inTransaction(c -> { assertTrue(new JdbcTestAttemptDao(c).listByRunCase(e.runCase().id()).isEmpty()); return null; });
    }

    @Test void callerLocksSerializeConcurrentAppendsWithoutAutomaticRetry() throws Exception {
        var e = tx.inTransaction(this::execution); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) futures.add(workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS)); var numbers = new ArrayList<Integer>();
                for (int i = 0; i < 4; i++) numbers.add(tx.inTransaction(c -> {
                    new JdbcTestRunDao(c).findByIdForUpdate(e.run().id()).orElseThrow();
                    new JdbcTestRunCaseDao(c).findByIdForUpdate(e.runCase().id()).orElseThrow();
                    var dao = new JdbcTestAttemptDao(c);
                    int next = dao.findLatestByRunCaseForUpdate(e.runCase().id()).map(a -> Math.addExact(a.attemptNo(), 1)).orElse(1);
                    return dao.insert(attempt(e, next, TestAttemptStatus.PASS)).attemptNo();
                }));
                return numbers;
            }));
            start.countDown(); var numbers = new ArrayList<Integer>();
            for (var future : futures) numbers.addAll(future.get(20, TimeUnit.SECONDS));
            assertEquals(IntStream.rangeClosed(1, 16).boxed().toList(), numbers.stream().sorted().toList());
        }
        tx.inTransaction(c -> {
            assertEquals(IntStream.rangeClosed(1, 16).boxed().toList(), new JdbcTestAttemptDao(c).listByRunCase(e.runCase().id()).stream().map(TestAttempt::attemptNo).toList()); return null;
        });
    }

    @Test void lockingLatestReadSeesCommitAfterEarlierRepeatableReadSnapshot() throws Exception {
        var e = tx.inTransaction(this::execution);
        try (var older = pool.borrow()) {
            older.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ); older.setAutoCommit(false);
            var dao = new JdbcTestAttemptDao(older); assertTrue(dao.findLatestByRunCase(e.runCase().id()).isEmpty());
            var first = tx.inTransaction(c -> new JdbcTestAttemptDao(c).insert(attempt(e, 1, TestAttemptStatus.FAIL)));
            assertTrue(dao.findLatestByRunCase(e.runCase().id()).isEmpty()); // Old read view is intentional.
            new JdbcTestRunDao(older).findByIdForUpdate(e.run().id()).orElseThrow();
            new JdbcTestRunCaseDao(older).findByIdForUpdate(e.runCase().id()).orElseThrow();
            assertEquals(first, dao.findLatestByRunCaseForUpdate(e.runCase().id()).orElseThrow());
            dao.insert(attempt(e, 2, TestAttemptStatus.PASS)); older.rollback();
        }
        tx.inTransaction(c -> { assertEquals(1, new JdbcTestAttemptDao(c).listByRunCase(e.runCase().id()).size()); return null; });
    }

    @Test void concurrentUncoordinatedSameSequenceIsRejectedByUniqueKey() throws Exception {
        var e = tx.inTransaction(this::execution); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) futures.add(workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    tx.inTransaction(c -> new JdbcTestAttemptDao(c).insert(attempt(e, 1, TestAttemptStatus.PASS)));
                    return 0;
                } catch (DataAccessException conflict) { return conflict.getVendorCode(); }
            }));
            start.countDown(); var results = new ArrayList<Integer>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(0, 1062), results.stream().sorted().toList());
        }
        tx.inTransaction(c -> { assertEquals(1, new JdbcTestAttemptDao(c).listByRunCase(e.runCase().id()).size()); return null; });
    }
}
