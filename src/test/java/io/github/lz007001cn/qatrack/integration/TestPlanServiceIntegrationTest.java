package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.DefaultTestPlanService;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.exception.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestPlanServiceIntegrationTest extends ServiceFixture {
    @Test void createReadyScopeChangesAndArchiveFollowFrozenWorkflow() {
        User admin = actor("admin-plan-flow", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-plan-flow", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "PLANFLOW", tester, ProjectRole.TESTER);
        TestCase first = readyCase(tester, project, "First", 1);
        TestCase second = readyCase(tester, project, "Second", 1);
        TestPlan plan = testPlans.create(tester.id(), new CreateTestPlanCommand(
                project.id(), "Regression", "scope", List.of(first.id())));
        assertEquals(1L, plan.keyNo());
        assertEquals(TestPlanStatus.DRAFT, plan.status());
        TestPlan ready = testPlans.update(tester.id(), new UpdateTestPlanCommand(
                plan.id(), plan.name(), plan.description(), TestPlanStatus.READY, plan.lockVersion()));
        assertEquals(TestPlanStatus.READY, ready.status());

        testPlans.addCase(tester.id(), ready.id(), second.id(), ready.lockVersion());
        TestPlan afterAdd = testPlans.get(tester.id(), ready.id());
        assertEquals(TestPlanStatus.DRAFT, afterAdd.status());
        assertEquals(2, testPlans.listCases(tester.id(), ready.id()).size());
        assertThrows(ConflictException.class,
                () -> testPlans.addCase(tester.id(), ready.id(), second.id(), afterAdd.lockVersion()));
        testPlans.removeCase(tester.id(), ready.id(), first.id(), afterAdd.lockVersion());
        TestPlan afterRemove = testPlans.get(tester.id(), ready.id());
        assertEquals(TestPlanStatus.DRAFT, afterRemove.status());
        assertEquals(List.of(second.id()), testPlans.listCases(tester.id(), ready.id()).stream()
                .map(TestPlanCase::testCaseId).toList());
        TestPlan archived = testPlans.archive(tester.id(), ready.id(), afterRemove.lockVersion());
        assertEquals(TestPlanStatus.ARCHIVED, archived.status());
        assertThrows(ConflictException.class,
                () -> testPlans.addCase(tester.id(), archived.id(), first.id(), archived.lockVersion()));
    }

    @Test void rejectsDuplicateCrossProjectArchivedProjectAndUnauthorizedActor() {
        User admin = actor("admin-plan-access", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-plan-access", SystemRole.USER, UserStatus.ACTIVE);
        User developer = actor("developer-plan-access", SystemRole.USER, UserStatus.ACTIVE);
        Project first = createProject(admin, "PLANACC", tester, ProjectRole.TESTER);
        addMember(first, developer, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        Project second = createProject(admin, "PLANOTHER", tester, ProjectRole.TESTER);
        TestCase firstCase = createCase(tester, first, "First", 1);
        TestCase otherCase = createCase(tester, second, "Other", 1);
        assertThrows(ValidationException.class, () -> testPlans.create(tester.id(),
                new CreateTestPlanCommand(first.id(), "Duplicate", null, List.of(firstCase.id(), firstCase.id()))));
        assertThrows(ForbiddenException.class, () -> testPlans.create(developer.id(),
                new CreateTestPlanCommand(first.id(), "Forbidden", null, List.of())));
        TestPlan plan = testPlans.create(tester.id(),
                new CreateTestPlanCommand(first.id(), "Plan", null, List.of(firstCase.id())));
        TestCase archivedCase = testCases.update(tester.id(), new UpdateTestCaseCommand(firstCase.id(),
                firstCase.title(), firstCase.description(), firstCase.preconditions(), firstCase.priority(),
                TestCaseStatus.ARCHIVED, firstCase.lockVersion(),
                List.of(new TestStepInput(1, "action 1", "expected 1"))));
        assertThrows(ConflictException.class, () -> testPlans.create(tester.id(),
                new CreateTestPlanCommand(first.id(), "No archived case", null, List.of(archivedCase.id()))));
        assertThrows(ValidationException.class,
                () -> testPlans.addCase(tester.id(), plan.id(), otherCase.id(), plan.lockVersion()));
        Project archived = projects.archive(admin.id(), second.id(), second.lockVersion());
        assertEquals(ProjectStatus.ARCHIVED, archived.status());
        assertThrows(ConflictException.class, () -> testPlans.create(tester.id(),
                new CreateTestPlanCommand(second.id(), "Archived", null, List.of())));
    }

    @Test void planCounterPlanAndRelationsRollbackTogether() {
        User admin = actor("admin-plan-fault", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-plan-fault", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "PLANFAULT", tester, ProjectRole.TESTER);
        TestCase first = createCase(tester, project, "First", 1);
        TestCase second = createCase(tester, project, "Second", 1);
        var failingFactory = (io.github.lz007001cn.qatrack.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var failing = new ServiceDaoDelegates.PlanCaseDelegate(d.testPlanCases()) {
                int writes;
                @Override public TestPlanCase add(TestPlanCase value) {
                    TestPlanCase saved = target.add(value);
                    if (++writes == 2) throw new DataAccessException("Injected plan scope failure");
                    return saved;
                }
            };
            return ServiceDaoDelegates.planCases(d, failing);
        };
        var service = new DefaultTestPlanService(serviceTx, failingFactory, access);
        assertThrows(DataAccessException.class, () -> service.create(tester.id(), new CreateTestPlanCommand(
                project.id(), "Rollback", null, List.of(first.id(), second.id()))));
        tx.inTransaction(c -> {
            assertTrue(new io.github.lz007001cn.qatrack.dao.jdbc.JdbcTestPlanDao(c)
                    .listByProject(project.id()).isEmpty());
            assertEquals(1L, new io.github.lz007001cn.qatrack.dao.jdbc.JdbcProjectCounterDao(c)
                    .find(project.id(), CounterEntityType.PLAN).orElseThrow().nextValue());
            return null;
        });
    }

    @Test void readyRequiresNonemptyScopeAndEveryCaseReady() {
        User admin = actor("admin-plan-ready", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-plan-ready", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "PLANREADY", tester, ProjectRole.TESTER);
        TestPlan empty = testPlans.create(tester.id(),
                new CreateTestPlanCommand(project.id(), "Empty", null, List.of()));
        assertThrows(ConflictException.class, () -> testPlans.update(tester.id(), new UpdateTestPlanCommand(
                empty.id(), empty.name(), empty.description(), TestPlanStatus.READY, empty.lockVersion())));
        TestCase draft = createCase(tester, project, "Draft", 1);
        TestPlan withDraft = testPlans.create(tester.id(),
                new CreateTestPlanCommand(project.id(), "Draft scope", null, List.of(draft.id())));
        assertThrows(ConflictException.class, () -> testPlans.update(tester.id(), new UpdateTestPlanCommand(
                withDraft.id(), withDraft.name(), null, TestPlanStatus.READY, withDraft.lockVersion())));
    }

    @Test void readyRevalidatesCurrentScopeAfterAnEarlierRepeatableRead() {
        User admin = actor("admin-plan-current", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-plan-current", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "PLANCURRENT", tester, ProjectRole.TESTER);
        TestCase first = readyCase(tester, project, "First", 1);
        TestCase added = readyCase(tester, project, "Added concurrently", 1);
        TestPlan plan = testPlans.create(tester.id(),
                new CreateTestPlanCommand(project.id(), "Current scope", null, List.of(first.id())));
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var changingFactory = (io.github.lz007001cn.qatrack.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var changing = new ServiceDaoDelegates.PlanCaseDelegate(d.testPlanCases()) {
                @Override public List<TestPlanCase> listByTestPlan(Long planId) {
                    List<TestPlanCase> snapshot = target.listByTestPlan(planId);
                    if (changed.compareAndSet(false, true)) {
                        try (var other = pool.borrow()) {
                            new io.github.lz007001cn.qatrack.dao.jdbc.JdbcTestPlanCaseDao(other).add(
                                    new TestPlanCase(planId, added.id(), tester.id(), null));
                        } catch (java.sql.SQLException failure) {
                            throw new DataAccessException("Close concurrent plan scope connection", failure);
                        }
                    }
                    return snapshot;
                }
            };
            return ServiceDaoDelegates.planCases(d, changing);
        };
        var service = new DefaultTestPlanService(serviceTx, changingFactory, access);
        assertThrows(ConflictException.class, () -> service.update(tester.id(), new UpdateTestPlanCommand(
                plan.id(), plan.name(), plan.description(), TestPlanStatus.READY, plan.lockVersion())));
        assertEquals(TestPlanStatus.DRAFT, testPlans.get(tester.id(), plan.id()).status());
    }
}
