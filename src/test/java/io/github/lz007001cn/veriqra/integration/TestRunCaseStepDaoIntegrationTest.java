package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestRunCaseStepDaoIntegrationTest extends ExecutionFixture {
    @Test void orderedSnapshotStepsSurviveCurrentStepReplacement() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestRunCaseStepDao(c); var current = new JdbcTestStepDao(c);
            current.add(new TestStep(e.testCase().id(), 1, "old ' ? 中文", "expected"));
            var second = dao.insert(new TestRunCaseStep(e.runCase().id(), 2, "second", "result"));
            var first = dao.insert(new TestRunCaseStep(e.runCase().id(), 1, "old ' ? 中文", "expected"));
            current.deleteByTestCase(e.testCase().id()); current.add(new TestStep(e.testCase().id(), 1, "new", "new"));
            assertEquals(List.of(first, second), dao.listByRunCase(e.runCase().id()));
            assertEquals(first, dao.find(e.runCase().id(), 1).orElseThrow());
            assertTrue(dao.find(e.runCase().id(), 99).isEmpty()); assertTrue(dao.listByRunCase(-1L).isEmpty()); return null;
        });
    }

    @Test void compositeKeyForeignKeyCheckAndSmallintRange() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestRunCaseStepDao(c); Long id = e.runCase().id();
            var maximum = dao.insert(new TestRunCaseStep(id, 65535, "action", "expected"));
            assertEquals(65535, maximum.stepOrder());
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(maximum)).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCaseStep(-1L, 1, "a", "e"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCaseStep(id, 0, "a", "e"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCaseStep(id, 1, " ", "e"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCaseStep(id, 1, "a", " "))).getVendorCode());
            assertEquals(1264, assertThrows(DataAccessException.class, () -> dao.insert(new TestRunCaseStep(id, 65536, "a", "e"))).getVendorCode()); return null;
        });
    }
}
