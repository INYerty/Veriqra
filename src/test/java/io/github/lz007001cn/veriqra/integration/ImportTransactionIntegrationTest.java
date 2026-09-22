package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportTransactionIntegrationTest extends ImportFixture {
    @Test void newRunSnapshotsMappingBatchAndAutomatedAttemptCommitTogether() {
        var p = tx.inTransaction(this::parents); var key = UUID.randomUUID();
        var e = tx.inTransaction(c -> {
            var t = new JdbcTestCaseDao(c).insert(testCase(p, 1));
            var identity = new JdbcTestAutomationIdentityDao(c).insert(identity(p, "key"));
            var mappings = new JdbcTestAutomationMappingDao(c);
            var mapping = mappings.add(mapping(identity.id(), t.id(), p.userId()));
            var r = new JdbcTestRunDao(c).insert(run(p, null));
            var rc = new JdbcTestRunCaseDao(c).insert(snapshot(r.id(), t));
            new JdbcTestRunCaseStepDao(c).insert(new TestRunCaseStep(rc.id(), 1, "action", "expected"));
            var execution = new Execution(p, t, r, rc);
            var batch = new JdbcTestImportDao(c).insert(batch(execution, key));
            assertEquals(mapping, mappings.findByIdentity(identity.id()).orElseThrow());
            var attempt = new JdbcTestAttemptDao(c).insert(automated(execution, batch, mapping, 1));
            assertNull(attempt.executedBy()); assertEquals(mapping.id(), attempt.automationMappingId());
            assertEquals(batch.id(), attempt.importId()); return execution;
        });
        tx.inTransaction(c -> {
            assertEquals(e.run(), new JdbcTestRunDao(c).findById(e.run().id()).orElseThrow());
            var batch = new JdbcTestImportDao(c).findByRequestKey(key).orElseThrow();
            assertEquals(e.run().id(), batch.testRunId());
            var attempts = new JdbcTestAttemptDao(c); var original = attempts.findLatestByRunCase(e.runCase().id()).orElseThrow();
            var manual = attempts.insert(attempt(e, 2, TestAttemptStatus.PASS));
            assertEquals(List.of(original, manual), attempts.listByRunCase(e.runCase().id()));
            assertEquals(1, new JdbcTestRunCaseStepDao(c).listByRunCase(e.runCase().id()).size()); return null;
        });
    }
    @Test void laterAttemptFailureRollsBackImportMappingIdentityAndEarlierEvidence() {
        var e = tx.inTransaction(this::execution); var key = UUID.randomUUID();
        var error = assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            var batch = new JdbcTestImportDao(c).insert(batch(e, key));
            var id = new JdbcTestAutomationIdentityDao(c).insert(identity(e.parents(), "rollback"));
            var mapping = new JdbcTestAutomationMappingDao(c).add(mapping(id.id(), e.testCase().id(), e.parents().userId()));
            var dao = new JdbcTestAttemptDao(c); dao.insert(automated(e, batch, mapping, 1));
            dao.insert(automated(e, batch, mapping, 2)); // UNIQUE(import_id,run_case_id), not attempt number or UUID.
            return null;
        }));
        assertEquals(1062, error.getVendorCode());
        tx.inTransaction(c -> {
            assertTrue(new JdbcTestImportDao(c).findByRequestKey(key).isEmpty());
            assertTrue(new JdbcTestImportDao(c).listByRun(e.run().id()).isEmpty());
            assertTrue(new JdbcTestAutomationMappingDao(c).listByTestCase(e.testCase().id()).isEmpty());
            assertTrue(new JdbcTestAutomationIdentityDao(c).listByProject(e.parents().projectId()).isEmpty());
            assertTrue(new JdbcTestAttemptDao(c).listByRunCase(e.runCase().id()).isEmpty());
            assertEquals(e.runCase(), new JdbcTestRunCaseDao(c).findById(e.runCase().id()).orElseThrow()); return null;
        });
    }
    @Test void crossProjectMappingAndUsedMappingRebindRemainServiceInvariants() {
        var e = tx.inTransaction(this::execution);
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            var otherProject = new JdbcProjectDao(c).insert(project("OTHER", e.parents().userId()));
            var otherCase = new JdbcTestCaseDao(c).insert(testCase(new Parents(e.parents().userId(), otherProject.id()), 1));
            var id = new JdbcTestAutomationIdentityDao(c).insert(identity(e.parents(), "key"));
            var mappings = new JdbcTestAutomationMappingDao(c);
            var wrong = mappings.add(mapping(id.id(), otherCase.id(), e.parents().userId())); // Accepted cross-project association.
            assertEquals(otherCase.id(), wrong.testCaseId());
            var corrected = mappings.update(new TestAutomationMapping(wrong.id(), id.id(), e.testCase().id(), wrong.status(), wrong.createdBy(), null, null, wrong.lockVersion()));
            var batch = new JdbcTestImportDao(c).insert(batch(e, UUID.randomUUID()));
            new JdbcTestAttemptDao(c).insert(automated(e, batch, corrected, 1));
            var illegalRebind = mappings.update(new TestAutomationMapping(corrected.id(), id.id(), otherCase.id(), corrected.status(), corrected.createdBy(), null, null, corrected.lockVersion()));
            assertEquals(otherCase.id(), illegalRebind.testCaseId()); // FK does not prevent rewriting a referenced mapping's Case.
            throw new IllegalStateException("rollback invariant probe");
        }));
        tx.inTransaction(c -> {
            assertTrue(new JdbcProjectDao(c).findByKey("OTHER").isEmpty());
            assertTrue(new JdbcTestAutomationIdentityDao(c).listByProject(e.parents().projectId()).isEmpty());
            assertTrue(new JdbcTestImportDao(c).listByRun(e.run().id()).isEmpty()); return null;
        });
    }
    @Test void attemptRunMappingCaseNamespaceAndActiveStateNeedServiceValidation() {
        var e = tx.inTransaction(this::execution);
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            var otherRun = new JdbcTestRunDao(c).insert(run(e.parents(), null));
            var otherCase = new JdbcTestCaseDao(c).insert(testCase(e.parents(), 2));
            var id = new JdbcTestAutomationIdentityDao(c).insert(identity(e.parents(), "key"));
            var mapping = new JdbcTestAutomationMappingDao(c).add(new TestAutomationMapping(null, id.id(), otherCase.id(),
                    AutomationMappingStatus.INACTIVE, e.parents().userId(), null, null, null));
            var batch = new JdbcTestImportDao(c).insert(new TestImport(null, otherRun.id(), UUID.randomUUID(), hash(), "mismatched namespace", "file.xml", e.parents().userId(), null));
            var attempt = new JdbcTestAttemptDao(c).insert(automated(e, batch, mapping, 1));
            assertNotNull(attempt.id()); // All FK endpoints exist, but the business relationships are wrong.
            throw new IllegalStateException("rollback invariant probe");
        }));
        tx.inTransaction(c -> {
            assertEquals(List.of(e.run()), new JdbcTestRunDao(c).listByProject(e.parents().projectId()));
            assertTrue(new JdbcTestAttemptDao(c).listByRunCase(e.runCase().id()).isEmpty()); return null;
        });
    }
}
