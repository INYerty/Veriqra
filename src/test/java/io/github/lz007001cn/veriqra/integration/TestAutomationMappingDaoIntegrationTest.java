package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestAutomationMappingDaoIntegrationTest extends ImportFixture {
    @Test void multipleIdentitiesPerCaseAndInactiveRowExistence() {
        tx.inTransaction(c -> {
            var e = execution(c); var ids = new JdbcTestAutomationIdentityDao(c); var dao = new JdbcTestAutomationMappingDao(c);
            var one = ids.insert(identity(e.parents(), "one")); var two = ids.insert(identity(e.parents(), "two"));
            assertFalse(dao.existsRecord(one.id()));
            var first = dao.add(mapping(one.id(), e.testCase().id(), e.parents().userId()));
            var second = dao.add(mapping(two.id(), e.testCase().id(), e.parents().userId()));
            assertEquals(first, dao.findById(first.id()).orElseThrow()); assertEquals(first, dao.findByIdentity(one.id()).orElseThrow());
            assertEquals(List.of(first, second), dao.listByTestCase(e.testCase().id()));
            var inactive = dao.update(new TestAutomationMapping(first.id(), -1L, first.testCaseId(), AutomationMappingStatus.INACTIVE,
                    -1L, null, null, first.lockVersion()));
            assertEquals(one.id(), inactive.automationIdentityId()); assertEquals(first.createdBy(), inactive.createdBy());
            assertEquals(first.createdAt(), inactive.createdAt()); assertEquals(1, inactive.lockVersion());
            assertTrue(dao.existsRecord(one.id())); assertEquals(AutomationMappingStatus.INACTIVE, dao.findByIdentity(one.id()).orElseThrow().status());
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.add(mapping(one.id(), e.testCase().id(), e.parents().userId()))).getVendorCode());
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.listByTestCase(-1L).isEmpty()); return null;
        });
    }
    @Test void correctionAndStatusUpdateUseOptimisticLockWithoutWorkflow() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestAutomationMappingDao(c);
            var id = new JdbcTestAutomationIdentityDao(c).insert(identity(e.parents(), "key"));
            var saved = dao.add(mapping(id.id(), e.testCase().id(), e.parents().userId()));
            var other = new JdbcTestCaseDao(c).insert(testCase(e.parents(), 2));
            var corrected = dao.update(new TestAutomationMapping(saved.id(), id.id(), other.id(), AutomationMappingStatus.ACTIVE,
                    saved.createdBy(), null, null, saved.lockVersion()));
            assertEquals(other.id(), corrected.testCaseId()); assertTrue(dao.listByTestCase(e.testCase().id()).isEmpty());
            assertThrows(OptimisticLockException.class, () -> dao.update(saved));
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.update(new TestAutomationMapping(saved.id(), id.id(), -1L,
                    corrected.status(), saved.createdBy(), null, null, corrected.lockVersion()))).getVendorCode()); return null;
        });
    }
    @Test void uniqueAndAllForeignKeysAreEnforced() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestAutomationMappingDao(c);
            var id = new JdbcTestAutomationIdentityDao(c).insert(identity(e.parents(), "key"));
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(mapping(-1L, e.testCase().id(), e.parents().userId()))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(mapping(id.id(), -1L, e.parents().userId()))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(mapping(id.id(), e.testCase().id(), -1L))).getVendorCode());
            var saved = dao.add(mapping(id.id(), e.testCase().id(), e.parents().userId()));
            var other = new JdbcTestCaseDao(c).insert(testCase(e.parents(), 2));
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.add(mapping(id.id(), other.id(), e.parents().userId()))).getVendorCode());
            assertEquals(1048, assertThrows(DataAccessException.class, () -> dao.update(new TestAutomationMapping(saved.id(), id.id(), saved.testCaseId(),
                    null, saved.createdBy(), null, null, saved.lockVersion()))).getVendorCode()); return null;
        });
    }
    @Test void mappingLockSeesCurrentStateAfterOldSnapshotAndRejectsAutocommit() throws Exception {
        var e = tx.inTransaction(this::execution);
        var id = tx.inTransaction(c -> new JdbcTestAutomationIdentityDao(c).insert(identity(e.parents(), "key")));
        var original = tx.inTransaction(c -> new JdbcTestAutomationMappingDao(c).add(mapping(id.id(), e.testCase().id(), e.parents().userId())));
        try (var c = pool.borrow()) {
            var dao = new JdbcTestAutomationMappingDao(c);
            assertEquals("25000", assertThrows(DataAccessException.class, () -> dao.findByIdentityForUpdate(id.id())).getSqlState());
            c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            assertEquals(original, dao.findByIdentity(id.id()).orElseThrow());
            var updated = tx.inTransaction(other -> {
                new JdbcTestAutomationIdentityDao(other).findByIdForUpdate(id.id()).orElseThrow();
                return new JdbcTestAutomationMappingDao(other).update(new TestAutomationMapping(original.id(), id.id(), original.testCaseId(),
                        AutomationMappingStatus.INACTIVE, original.createdBy(), null, null, original.lockVersion()));
            });
            new JdbcTestAutomationIdentityDao(c).findByIdForUpdate(id.id()).orElseThrow();
            assertEquals(updated, dao.findByIdentityForUpdate(id.id()).orElseThrow()); c.rollback();
        }
    }
}
