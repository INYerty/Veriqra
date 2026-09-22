package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestCaseDaoIntegrationTest extends AssetFixture {
    @Test void roundTripOrderingImmutableIdentityAndOptimisticLock() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcTestCaseDao(c);
            var old = dao.insert(testCase(p, 2));
            var first = dao.insert(testCase(p, 1));
            var otherProject = new JdbcProjectDao(c).insert(project("OTHER", p.userId()));
            dao.insert(testCase(new Parents(p.userId(), otherProject.id()), 1));
            assertEquals(List.of(first, old), dao.listByProject(p.projectId()));
            assertEquals(old, dao.findByKey(p.projectId(), 2L).orElseThrow());
            assertEquals(old, dao.findByIdForUpdate(old.id()).orElseThrow());
            assertNull(old.description()); assertNotNull(old.createdAt()); assertNotNull(old.updatedAt());
            assertEquals(0, old.lockVersion());
            var saved = dao.update(new TestCase(old.id(), -1L, 99L, "edited", "description", "前置条件", Priority.HIGH, TestCaseStatus.READY, -1L, null, null, old.lockVersion()));
            assertEquals(p.projectId(), saved.projectId()); assertEquals(2L, saved.keyNo());
            assertEquals(p.userId(), saved.createdBy()); assertEquals(old.createdAt(), saved.createdAt());
            assertEquals("description", saved.description()); assertEquals(TestCaseStatus.READY, saved.status());
            assertEquals(1, saved.lockVersion()); assertEquals(0, old.lockVersion());
            assertThrows(OptimisticLockException.class, () -> dao.update(old));
            assertFalse(c.isClosed()); assertFalse(c.getAutoCommit());
            return null;
        });
    }

    @Test void uniqueForeignKeyAndCheckErrorsAreTranslated() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcTestCaseDao(c);
            dao.insert(testCase(p, 1));
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(testCase(p, 1))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class,
                    () -> dao.insert(testCase(new Parents(p.userId(), -1L), 2))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class,
                    () -> dao.insert(testCase(new Parents(-1L, p.projectId()), 2))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(testCase(p, 0))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestCase(null, p.projectId(), 3L, " ", null, null, Priority.LOW, TestCaseStatus.READY, p.userId(), null, null, null))).getVendorCode());
            return null;
        });
    }

    @Test void absentLookupsAndLockWithoutTransaction() throws Exception {
        try (var c = pool.borrow()) {
            var dao = new JdbcTestCaseDao(c);
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.findById(null).isEmpty());
            assertTrue(dao.findByKey(-1L, 1L).isEmpty()); assertTrue(dao.listByProject(-1L).isEmpty());
            assertEquals("25000", assertThrows(DataAccessException.class, () -> dao.findByIdForUpdate(-1L)).getSqlState());
        }
    }
}
