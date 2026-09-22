package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestRunDaoIntegrationTest extends ExecutionFixture {
    @Test void adHocAndPlanRunsHaveStableOwnershipAndOrderedQueries() {
        tx.inTransaction(c -> {
            var p = parents(c); var plan = new JdbcTestPlanDao(c).insert(testPlan(p, 1));
            var dao = new JdbcTestRunDao(c);
            var adHoc = dao.insert(run(p, null)); var planned = dao.insert(run(p, plan.id()));
            assertNull(adHoc.testPlanId()); assertNotNull(adHoc.createdAt()); assertEquals(0, adHoc.lockVersion());
            assertEquals(adHoc, dao.findById(adHoc.id()).orElseThrow());
            assertEquals(List.of(adHoc, planned), dao.listByProject(p.projectId()));
            assertEquals(List.of(planned), dao.listByPlan(plan.id()));
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.listByPlan(-1L).isEmpty());
            assertTrue(dao.listByProject(-1L).isEmpty());
            return null;
        });
    }

    @Test void updatePreservesOriginAndRejectsStaleVersion() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcTestRunDao(c); var saved = dao.insert(run(p, null));
            var ended = saved.createdAt().plusSeconds(1).withNano(123456000);
            var edited = new TestRun(saved.id(), -1L, -1L, "completed", "local", "build-2",
                    TestRunStatus.COMPLETED, ended, -1L, null, null, saved.lockVersion());
            var updated = dao.update(edited);
            assertEquals(p.projectId(), updated.projectId()); assertNull(updated.testPlanId());
            assertEquals(p.userId(), updated.createdBy()); assertEquals(saved.createdAt(), updated.createdAt());
            assertEquals(ended, updated.endedAt()); assertEquals("local", updated.environment());
            assertEquals("build-2", updated.buildVersion()); assertEquals(1, updated.lockVersion());
            assertEquals(TestRunStatus.COMPLETED, updated.status());
            assertThrows(OptimisticLockException.class, () -> dao.update(edited));
            assertFalse(c.getAutoCommit()); assertFalse(c.isClosed()); return null;
        });
    }

    @Test void foreignKeysRequiredProjectAndEndShapeAreEnforced() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcTestRunDao(c);
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(run(p, -1L))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(run(new Parents(p.userId(), -1L), null))).getVendorCode());
            assertEquals(1048, assertThrows(DataAccessException.class, () -> dao.insert(run(new Parents(p.userId(), null), null))).getVendorCode());
            var r = dao.insert(run(p, null));
            for (var status : List.of(TestRunStatus.COMPLETED, TestRunStatus.CANCELLED)) {
                assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.update(new TestRun(r.id(), r.projectId(), null,
                        r.name(), null, null, status, null, r.createdBy(), null, null, 0))).getVendorCode());
            }
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.update(new TestRun(r.id(), r.projectId(), null,
                    " ", null, null, r.status(), null, r.createdBy(), null, null, 0))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.update(new TestRun(r.id(), r.projectId(), null,
                    r.name(), null, null, r.status(), r.createdAt(), r.createdBy(), null, null, 0))).getVendorCode());
            return null;
        });
    }

    @Test void lockingReadsRequireOuterTransaction() throws Exception {
        var e = tx.inTransaction(this::execution);
        try (var c = pool.borrow()) {
            assertEquals("25000", assertThrows(DataAccessException.class, () -> new JdbcTestRunDao(c).findByIdForUpdate(e.run().id())).getSqlState());
            assertEquals("25000", assertThrows(DataAccessException.class, () -> new JdbcTestRunCaseDao(c).findByIdForUpdate(e.runCase().id())).getSqlState());
            assertEquals("25000", assertThrows(DataAccessException.class, () -> new JdbcTestAttemptDao(c).findLatestByRunCaseForUpdate(e.runCase().id())).getSqlState());
        }
        tx.inTransaction(c -> {
            assertEquals(e.run(), new JdbcTestRunDao(c).findByIdForUpdate(e.run().id()).orElseThrow());
            assertEquals(e.runCase(), new JdbcTestRunCaseDao(c).findByIdForUpdate(e.runCase().id()).orElseThrow());
            assertTrue(new JdbcTestAttemptDao(c).findLatestByRunCaseForUpdate(e.runCase().id()).isEmpty()); return null;
        });
    }
}
