package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTransactionIntegrationTest extends ExecutionFixture {
    @Test void runPlanAndRunCaseProjectConsistencyRequireServiceChecks() {
        var e = tx.inTransaction(this::execution);
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            var other = new JdbcProjectDao(c).insert(project("OTHER", e.parents().userId()));
            var otherParents = new Parents(e.parents().userId(), other.id());
            var plan = new JdbcTestPlanDao(c).insert(testPlan(otherParents, 1));
            var foreignCase = new JdbcTestCaseDao(c).insert(testCase(otherParents, 1));
            var mixedRun = new JdbcTestRunDao(c).insert(run(e.parents(), plan.id()));
            var mixedSnapshot = new JdbcTestRunCaseDao(c).insert(snapshot(e.run().id(), foreignCase));
            assertNotNull(mixedRun.id()); assertNotNull(mixedSnapshot.id());
            throw new IllegalStateException("rollback accepted cross-project probes");
        }));
        tx.inTransaction(c -> {
            assertEquals(List.of(e.run()), new JdbcTestRunDao(c).listByProject(e.parents().projectId()));
            assertEquals(List.of(e.runCase()), new JdbcTestRunCaseDao(c).listByRun(e.run().id()));
            assertTrue(new JdbcProjectDao(c).findByKey("OTHER").isEmpty()); return null;
        });
    }

    @Test void laterSnapshotFailureRollsBackEntireNewRunAndAllEarlierChildren() {
        var p = tx.inTransaction(this::parents);
        var cases = tx.inTransaction(c -> List.of(new JdbcTestCaseDao(c).insert(testCase(p, 1)), new JdbcTestCaseDao(c).insert(testCase(p, 2))));
        var runId = new AtomicReference<Long>(); var snapshotIds = new ArrayList<Long>();
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            var r = new JdbcTestRunDao(c).insert(run(p, null)); runId.set(r.id());
            for (var t : cases) {
                var rc = new JdbcTestRunCaseDao(c).insert(snapshot(r.id(), t)); snapshotIds.add(rc.id());
                new JdbcTestRunCaseStepDao(c).insert(new TestRunCaseStep(rc.id(), 1, "step 1", "expected"));
                new JdbcTestRunCaseStepDao(c).insert(new TestRunCaseStep(rc.id(), 2, "step 2", "expected"));
            }
            new JdbcTestRunCaseStepDao(c).insert(new TestRunCaseStep(snapshotIds.getLast(), 2, "duplicate", "expected")); return null;
        }));
        tx.inTransaction(c -> {
            assertNotNull(runId.get()); assertEquals(2, snapshotIds.size());
            assertTrue(new JdbcTestRunDao(c).findById(runId.get()).isEmpty());
            assertTrue(new JdbcTestRunDao(c).listByProject(p.projectId()).isEmpty());
            for (var id : snapshotIds) {
                assertTrue(new JdbcTestRunCaseDao(c).findById(id).isEmpty());
                assertTrue(new JdbcTestRunCaseStepDao(c).listByRunCase(id).isEmpty());
            }
            assertEquals(2, new JdbcTestCaseDao(c).listByProject(p.projectId()).size()); return null;
        });
    }

    @Test void runAndMultipleSnapshotsCommitTogether() {
        var p = tx.inTransaction(this::parents);
        var r = tx.inTransaction(c -> {
            var run = new JdbcTestRunDao(c).insert(run(p, null));
            for (int i = 1; i <= 2; i++) {
                var t = new JdbcTestCaseDao(c).insert(testCase(p, i));
                var snapshot = new JdbcTestRunCaseDao(c).insert(snapshot(run.id(), t));
                for (int step = 1; step <= 2; step++) new JdbcTestRunCaseStepDao(c).insert(new TestRunCaseStep(snapshot.id(), step, "action", "expected"));
            }
            return run;
        });
        tx.inTransaction(c -> {
            assertEquals(r, new JdbcTestRunDao(c).findById(r.id()).orElseThrow());
            var snapshots = new JdbcTestRunCaseDao(c).listByRun(r.id()); assertEquals(2, snapshots.size());
            for (var snapshot : snapshots) assertEquals(2, new JdbcTestRunCaseStepDao(c).listByRunCase(snapshot.id()).size()); return null;
        });
    }

    @Test void counterDefectAndEvidenceCommitAsOneTransaction() {
        var e = tx.inTransaction(c -> {
            var saved = execution(c); new JdbcProjectCounterDao(c).insert(new ProjectCounter(saved.parents().projectId(), CounterEntityType.BUG, 1L)); return saved;
        });
        var fail = tx.inTransaction(c -> new JdbcTestAttemptDao(c).insert(attempt(e, 1, TestAttemptStatus.FAIL)));
        var bug = tx.inTransaction(c -> {
            long key = new JdbcProjectCounterDao(c).allocateNext(e.parents().projectId(), CounterEntityType.BUG);
            var d = new JdbcDefectDao(c).insert(defect(e.parents(), key));
            new JdbcTestAttemptDefectDao(c).add(new TestAttemptDefect(fail.id(), d.id(), e.parents().userId(), null)); return d;
        });
        tx.inTransaction(c -> {
            assertEquals(1L, bug.keyNo()); assertEquals(bug, new JdbcDefectDao(c).findById(bug.id()).orElseThrow());
            assertTrue(new JdbcTestAttemptDefectDao(c).existsRecord(fail.id(), bug.id()));
            assertEquals(2L, new JdbcProjectCounterDao(c).find(e.parents().projectId(), CounterEntityType.BUG).orElseThrow().nextValue()); return null;
        });
    }

    @Test void evidenceFailureRollsBackDefectAndBugNumberAllocation() {
        var e = tx.inTransaction(c -> {
            var saved = execution(c); new JdbcProjectCounterDao(c).insert(new ProjectCounter(saved.parents().projectId(), CounterEntityType.BUG, 1L)); return saved;
        });
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            long key = new JdbcProjectCounterDao(c).allocateNext(e.parents().projectId(), CounterEntityType.BUG);
            var bug = new JdbcDefectDao(c).insert(defect(e.parents(), key));
            new JdbcTestAttemptDefectDao(c).add(new TestAttemptDefect(-1L, bug.id(), e.parents().userId(), null)); return null;
        }));
        tx.inTransaction(c -> {
            assertTrue(new JdbcDefectDao(c).listByProject(e.parents().projectId()).isEmpty());
            assertEquals(1L, new JdbcProjectCounterDao(c).find(e.parents().projectId(), CounterEntityType.BUG).orElseThrow().nextValue()); return null;
        });
    }
}
