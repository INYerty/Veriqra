package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestCaseServiceIntegrationTest extends ServiceFixture {
    @Test void createAllocatesTcAndWritesContiguousStepsAtomically() {
        User admin = actor("admin-case", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-case", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "CASES", tester, ProjectRole.TESTER);
        TestCase saved = createCase(tester, project, "Case", 2);
        assertEquals(1L, saved.keyNo()); assertEquals(TestCaseStatus.DRAFT, saved.status());
        assertEquals(List.of(1, 2), testCases.listSteps(tester.id(), saved.id()).stream().map(TestStep::stepOrder).toList());
        assertThrows(ValidationException.class, () -> testCases.create(tester.id(), new CreateTestCaseCommand(
                project.id(), "Bad", null, null, Priority.MEDIUM,
                List.of(new TestStepInput(1, "A", "EA"), new TestStepInput(3, "B", "EB")))));
        tx.inTransaction(c -> {
            assertEquals(2L, new io.github.lz007001cn.veriqra.dao.jdbc.JdbcProjectCounterDao(c)
                    .find(project.id(), CounterEntityType.TC).orElseThrow().nextValue()); return null;
        });
    }

    @Test void stepFailureRollsBackCaseEarlierStepAndCounter() {
        User admin = actor("admin-case-fault", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-case-fault", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "CASEFAIL", tester, ProjectRole.TESTER);
        var failingFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var failing = new ServiceDaoDelegates.StepDelegate(d.steps()) {
                int writes;
                @Override public TestStep add(TestStep value) {
                    TestStep saved = target.add(value);
                    if (++writes == 2) throw new DataAccessException("Injected failure after second step");
                    return saved;
                }
            };
            return ServiceDaoDelegates.steps(d, failing);
        };
        var service = new DefaultTestCaseService(serviceTx, failingFactory, access);
        assertThrows(DataAccessException.class, () -> service.create(tester.id(), new CreateTestCaseCommand(
                project.id(), "Rollback", null, null, Priority.MEDIUM,
                List.of(new TestStepInput(1, "A", "EA"), new TestStepInput(2, "B", "EB")))));
        tx.inTransaction(c -> {
            assertTrue(new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestCaseDao(c).listByProject(project.id()).isEmpty());
            assertEquals(1L, new io.github.lz007001cn.veriqra.dao.jdbc.JdbcProjectCounterDao(c)
                    .find(project.id(), CounterEntityType.TC).orElseThrow().nextValue()); return null;
        });
    }

    @Test void currentDefinitionEditReturnsDraftInvalidatesLinkAndPreservesRunSnapshot() {
        User admin = actor("admin-case-history", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-case-history", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "HISTORY", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, project, "Requirement");
        TestCase current = createCase(tester, project, "Before", 1);
        traceability.attach(tester.id(), requirement.id(), current.id());
        traceability.confirm(tester.id(), requirement.id(), current.id());
        TestCase ready = testCases.update(tester.id(), new UpdateTestCaseCommand(current.id(), current.title(),
                current.description(), current.preconditions(), Priority.HIGH, TestCaseStatus.READY,
                current.lockVersion(), List.of(new TestStepInput(1, "action 1", "expected 1"))));
        assertEquals(TestCaseStatus.READY, ready.status());
        assertTrue(traceability.isConfirmedLink(tester.id(), requirement.id(), current.id()));
        TestRunCase snapshot = tx.inTransaction(c -> {
            var run = new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestRunDao(c).insert(new TestRun(null,
                    project.id(), null, "Run", null, null, TestRunStatus.IN_PROGRESS, null, tester.id(), null, null, null));
            var snap = new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestRunCaseDao(c).insert(new TestRunCase(null,
                    run.id(), ready.id(), ready.title(), ready.description(), ready.preconditions(), ready.priority(), null));
            new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestRunCaseStepDao(c)
                    .insert(new TestRunCaseStep(snap.id(), 1, "action 1", "expected 1")); return snap;
        });
        TestCase updated = testCases.update(tester.id(), new UpdateTestCaseCommand(ready.id(), "After",
                ready.description(), ready.preconditions(), Priority.HIGH, TestCaseStatus.READY,
                ready.lockVersion(), List.of(new TestStepInput(1, "new action", "new expected"))));
        assertEquals(TestCaseStatus.DRAFT, updated.status());
        assertFalse(traceability.isConfirmedLink(tester.id(), requirement.id(), current.id()));
        tx.inTransaction(c -> {
            assertEquals("Before", new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestRunCaseDao(c)
                    .findById(snapshot.id()).orElseThrow().snapshotTitle());
            assertEquals("action 1", new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestRunCaseStepDao(c)
                    .listByRunCase(snapshot.id()).getFirst().action()); return null;
        });
        assertThrows(ConflictException.class, () -> testCases.update(tester.id(), new UpdateTestCaseCommand(
                current.id(), "Stale", null, null, Priority.LOW, TestCaseStatus.DRAFT, current.lockVersion(), List.of())));
    }

    @Test void partialStepReplacementFailureRestoresCaseOldStepsAndConfirmedLink() {
        User admin = actor("admin-case-update-fault", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-case-update-fault", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "CASEUPFAIL", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, project, "Requirement");
        TestCase current = createCase(tester, project, "Original", 1);
        traceability.attach(tester.id(), requirement.id(), current.id());
        traceability.confirm(tester.id(), requirement.id(), current.id());
        var failingFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var failing = new ServiceDaoDelegates.StepDelegate(d.steps()) {
                int writes;
                @Override public TestStep add(TestStep value) {
                    TestStep saved = target.add(value);
                    if (++writes == 2) throw new DataAccessException("Injected failure during step replacement");
                    return saved;
                }
            };
            return ServiceDaoDelegates.steps(d, failing);
        };
        var service = new DefaultTestCaseService(serviceTx, failingFactory, access);
        assertThrows(DataAccessException.class, () -> service.update(tester.id(), new UpdateTestCaseCommand(
                current.id(), "Changed", current.description(), current.preconditions(), current.priority(),
                TestCaseStatus.READY, current.lockVersion(),
                List.of(new TestStepInput(1, "new 1", "expected 1"),
                        new TestStepInput(2, "new 2", "expected 2")))));
        TestCase restored = testCases.get(tester.id(), current.id());
        assertEquals(current.title(), restored.title());
        assertEquals(current.lockVersion(), restored.lockVersion());
        assertEquals(List.of(new TestStep(current.id(), 1, "action 1", "expected 1")),
                testCases.listSteps(tester.id(), current.id()));
        assertTrue(traceability.isConfirmedLink(tester.id(), requirement.id(), current.id()));
    }

    @Test void readyRequiresAtLeastOneStep() {
        User admin = actor("admin-ready", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-ready", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "READY", tester, ProjectRole.TESTER);
        TestCase draft = createCase(tester, project, "Draft", 0);
        assertThrows(ValidationException.class, () -> testCases.update(tester.id(), new UpdateTestCaseCommand(
                draft.id(), draft.title(), draft.description(), draft.preconditions(), draft.priority(),
                TestCaseStatus.READY, draft.lockVersion(), List.of())));
    }
}
