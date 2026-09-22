package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestRunCaseDaoIntegrationTest extends ExecutionFixture {
    @Test void snapshotStaysIndependentAfterCurrentDefinitionChanges() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestRunCaseDao(c); var current = e.testCase();
            var other = new JdbcTestCaseDao(c).insert(testCase(e.parents(), 2));
            var full = dao.insert(new TestRunCase(null, e.run().id(), other.id(), "snapshot title", "description ' ? 中文",
                    "preconditions", Priority.HIGH, null));
            assertEquals("description ' ? 中文", full.snapshotDescription()); assertEquals("preconditions", full.snapshotPreconditions());
            assertEquals(Priority.HIGH, full.snapshotPriority()); assertNotNull(full.capturedAt());
            new JdbcTestCaseDao(c).update(new TestCase(current.id(), current.projectId(), current.keyNo(), "changed",
                    "new", "new", Priority.LOW, current.status(), current.createdBy(), null, null, current.lockVersion()));
            assertEquals(e.runCase(), dao.findById(e.runCase().id()).orElseThrow());
            assertEquals(e.runCase(), dao.findByRunAndCase(e.run().id(), current.id()).orElseThrow());
            var nextRun = new JdbcTestRunDao(c).insert(run(e.parents(), null));
            var next = dao.insert(snapshot(nextRun.id(), current));
            assertEquals(List.of(e.runCase(), next), dao.listByTestCase(current.id()));
            assertEquals(List.of(e.runCase(), full), dao.listByRun(e.run().id()));
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.listByRun(-1L).isEmpty()); return null;
        });
    }

    @Test void runCaseUniqueForeignKeyAndSnapshotCheckConvertErrors() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestRunCaseDao(c);
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(e.runCase())).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(snapshot(-1L, e.testCase()))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCase(null, e.run().id(), -1L,
                    "valid", null, null, Priority.LOW, null))).getVendorCode());
            var next = new JdbcTestRunDao(c).insert(run(e.parents(), null));
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCase(null, next.id(), e.testCase().id(),
                    " ", null, null, Priority.LOW, null))).getVendorCode());
            return null;
        });
    }
}
