package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestStepDaoIntegrationTest extends AssetFixture {
    @Test void compositeIdentityOrderAndContentUpdate() {
        tx.inTransaction(c -> {
            var p = parents(c); var t = new JdbcTestCaseDao(c).insert(testCase(p, 1));
            var dao = new JdbcTestStepDao(c);
            var second = dao.add(new TestStep(t.id(), 2, "second", "result 2"));
            var first = dao.add(new TestStep(t.id(), 1, "first ' ? 中文", "result 1"));
            assertEquals(List.of(first, second), dao.listByTestCase(t.id()));
            var edited = new TestStep(t.id(), 1, "edit", "expected");
            assertTrue(dao.updateContent(edited)); assertEquals(edited, dao.find(t.id(), 1).orElseThrow());
            assertTrue(dao.remove(t.id(), 2)); assertFalse(dao.remove(t.id(), 2));
            assertFalse(dao.updateContent(new TestStep(t.id(), 9, "absent", "absent")));
            assertTrue(dao.find(t.id(), null).isEmpty()); assertTrue(dao.listByTestCase(-1L).isEmpty());
            return null;
        });
    }

    @Test void uniqueForeignKeyCheckAndUnsignedSmallintBoundary() {
        tx.inTransaction(c -> {
            var p = parents(c); var t = new JdbcTestCaseDao(c).insert(testCase(p, 1)); var dao = new JdbcTestStepDao(c);
            var maximum = dao.add(new TestStep(t.id(), 65535, "last", "expected"));
            assertEquals(65535, maximum.stepOrder());
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.add(maximum)).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new TestStep(-1L, 1, "a", "e"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.add(new TestStep(t.id(), 0, "a", "e"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.add(new TestStep(t.id(), 1, " ", "e"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.add(new TestStep(t.id(), 1, "a", " "))).getVendorCode());
            assertEquals(1264, assertThrows(DataAccessException.class, () -> dao.add(new TestStep(t.id(), 65536, "a", "e"))).getVendorCode());
            return null;
        });
    }

    @Test void replaceStepsAndParentVersionCommitTogether() {
        tx.inTransaction(c -> {
            var p = parents(c); var parent = new JdbcTestCaseDao(c); var t = parent.insert(testCase(p, 1));
            var dao = new JdbcTestStepDao(c);
            dao.add(new TestStep(t.id(), 1, "A", "EA")); dao.add(new TestStep(t.id(), 2, "B", "EB"));
            var locked = parent.findByIdForUpdate(t.id()).orElseThrow();
            parent.update(locked); // Caller-controlled parent version participates in the same transaction.
            assertEquals(2, dao.deleteByTestCase(t.id()));
            dao.add(new TestStep(t.id(), 1, "B", "EB")); dao.add(new TestStep(t.id(), 2, "A", "EA"));
            assertEquals(List.of("B", "A"), dao.listByTestCase(t.id()).stream().map(TestStep::action).toList());
            assertEquals(1, parent.findById(t.id()).orElseThrow().lockVersion()); return null;
        });
    }

    @Test void replacementFailureRollsBackDeletionInsertAndParentVersion() {
        var t = tx.inTransaction(c -> {
            var p = parents(c); var saved = new JdbcTestCaseDao(c).insert(testCase(p, 1));
            new JdbcTestStepDao(c).add(new TestStep(saved.id(), 1, "original", "expected")); return saved;
        });
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            var parent = new JdbcTestCaseDao(c); var dao = new JdbcTestStepDao(c);
            parent.update(parent.findByIdForUpdate(t.id()).orElseThrow());
            dao.deleteByTestCase(t.id());
            dao.add(new TestStep(t.id(), 1, "replacement", "new"));
            dao.add(new TestStep(t.id(), 1, "duplicate", "new")); return null;
        }));
        tx.inTransaction(c -> {
            assertEquals(List.of(new TestStep(t.id(), 1, "original", "expected")), new JdbcTestStepDao(c).listByTestCase(t.id()));
            assertEquals(t, new JdbcTestCaseDao(c).findById(t.id()).orElseThrow()); return null;
        });
    }
}
