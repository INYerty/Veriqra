package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestAutomationIdentityDaoIntegrationTest extends ImportFixture {
    @Test void projectSourceNamespaceAndExactKeyFormIdentity() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcTestAutomationIdentityDao(c);
            var first = dao.insert(identity(p, "Class#Test ' ? 中文"));
            assertEquals(first, dao.findById(first.id()).orElseThrow());
            assertEquals(first, dao.findByExternalKey(p.projectId(), AutomationSource.JUNIT, first.namespace(), first.externalKey()).orElseThrow());
            assertNotNull(first.createdAt()); assertEquals(AutomationSource.JUNIT, first.source());
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(identity(p, first.externalKey()))).getVendorCode());
            var otherProject = new JdbcProjectDao(c).insert(project("OTHER", p.userId()));
            var other = dao.insert(identity(new Parents(p.userId(), otherProject.id()), first.externalKey()));
            assertNotEquals(first.id(), other.id()); assertEquals(List.of(other), dao.listByProject(otherProject.id()));
            dao.insert(identity(p, "class#Test ' ? 中文")); // Binary collation preserves case.
            dao.insert(identity(p, first.externalKey() + " ")); // NO PAD preserves trailing space.
            dao.insert(new TestAutomationIdentity(null, p.projectId(), AutomationSource.JUNIT, "another", first.externalKey(), null));
            assertEquals(4, dao.listByProject(p.projectId()).size());
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.listByProject(-1L).isEmpty());
            assertTrue(dao.findByExternalKey(p.projectId(), AutomationSource.JUNIT, "absent", first.externalKey()).isEmpty()); return null;
        });
    }
    @Test void foreignKeyCheckAndFrozenSourceRejectInvalidRows() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcTestAutomationIdentityDao(c);
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(identity(new Parents(p.userId(), -1L), "key"))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(identity(p, ""))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestAutomationIdentity(null, p.projectId(), AutomationSource.JUNIT, " ", "key", null))).getVendorCode());
            assertEquals(1048, assertThrows(DataAccessException.class, () -> dao.insert(new TestAutomationIdentity(null, p.projectId(), null, "module", "key", null))).getVendorCode());
            var saved = dao.insert(identity(p, "key"));
            try (var s = c.prepareStatement("UPDATE test_automation_identities SET source=? WHERE id=?")) {
                s.setString(1, "PLAYWRIGHT"); s.setLong(2, saved.id());
                assertEquals(3819, assertThrows(java.sql.SQLException.class, s::executeUpdate).getErrorCode());
            }
            return null;
        });
    }
    @Test void identityLockRequiresAnOuterTransaction() throws Exception {
        var id = tx.inTransaction(c -> new JdbcTestAutomationIdentityDao(c).insert(identity(parents(c), "key")));
        try (var c = pool.borrow()) {
            assertEquals("25000", assertThrows(DataAccessException.class, () -> new JdbcTestAutomationIdentityDao(c).findByIdForUpdate(id.id())).getSqlState());
        }
        tx.inTransaction(c -> { assertEquals(id, new JdbcTestAutomationIdentityDao(c).findByIdForUpdate(id.id()).orElseThrow()); return null; });
    }
}
