package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.exception.OptimisticLockException;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.exception.*;
import io.github.lz007001cn.qatrack.service.support.*;
import java.util.*;

public final class DefaultTestPlanService implements TestPlanService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daoFactory;
    private final ProjectAccessPolicy access;

    public DefaultTestPlanService(ServiceTransaction transactions, ServiceDaoFactory daoFactory,
                                  ProjectAccessPolicy access) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daoFactory = Objects.requireNonNull(daoFactory);
        this.access = Objects.requireNonNull(access);
    }

    @Override public TestPlan create(Long actorUserId, CreateTestPlanCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.projectId(), "projectId");
        String name = ServiceValidation.requiredText(command.name(), 160, "name");
        List<Long> caseIds = distinctCaseIds(command.testCaseIds(), false);
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Project project = writableProject(daos, actorUserId, command.projectId());
            long key = daos.counters().allocateNext(project.id(), CounterEntityType.PLAN);
            List<TestCase> cases = lockCases(daos, project.id(), caseIds, false);
            TestPlan plan = daos.testPlans().insert(new TestPlan(null, project.id(), key, name,
                    command.description(), TestPlanStatus.DRAFT, actorUserId, null, null, null));
            for (TestCase testCase : cases) {
                daos.testPlanCases().add(new TestPlanCase(plan.id(), testCase.id(), actorUserId, null));
            }
            return plan;
        });
    }

    @Override public TestPlan get(Long actorUserId, Long testPlanId) {
        ServiceValidation.required(testPlanId, "testPlanId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan plan = daos.testPlans().findById(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, plan.projectId());
            return plan;
        });
    }

    @Override public List<TestPlanCase> listCases(Long actorUserId, Long testPlanId) {
        ServiceValidation.required(testPlanId, "testPlanId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan plan = daos.testPlans().findById(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, plan.projectId());
            return daos.testPlanCases().listByTestPlan(plan.id());
        });
    }

    @Override public TestPlan update(Long actorUserId, UpdateTestPlanCommand command) {
        return update(actorUserId, null, command);
    }

    @Override public TestPlan update(Long actorUserId, Long projectId, UpdateTestPlanCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.testPlanId(), "testPlanId");
        ServiceValidation.required(command.lockVersion(), "lockVersion");
        String name = ServiceValidation.requiredText(command.name(), 160, "name");
        TestPlanStatus requestedStatus = ServiceValidation.required(command.status(), "status");
        if (requestedStatus == TestPlanStatus.ARCHIVED) {
            throw new ValidationException("Use archive to place a test plan in ARCHIVED state");
        }
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan preliminary = daos.testPlans().findById(command.testPlanId())
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            Project project = writableProject(daos, actorUserId, preliminary.projectId());
            ProjectOwnership.require(preliminary.projectId(), projectId);
            List<Long> expectedCaseIds = requestedStatus == TestPlanStatus.READY
                    ? planCaseIds(daos, preliminary.id()) : List.of();
            List<TestCase> cases = requestedStatus == TestPlanStatus.READY
                    ? lockCases(daos, project.id(), expectedCaseIds, true) : List.of();
            TestPlan current = daos.testPlans().findByIdForUpdate(preliminary.id())
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            ensureSameProject(current.projectId(), project.id(), "Test plan");
            if (current.status() == TestPlanStatus.ARCHIVED) {
                throw new ConflictException("Archived test plan is read-only");
            }
            if (requestedStatus == TestPlanStatus.READY) {
                List<Long> lockedCaseIds = lockedPlanCaseIds(daos, current.id());
                if (!expectedCaseIds.equals(lockedCaseIds)) {
                    throw new ConflictException("Test plan scope changed during update");
                }
                if (cases.isEmpty()) throw new ConflictException("READY test plan requires at least one test case");
            }
            try {
                return daos.testPlans().update(new TestPlan(current.id(), current.projectId(), current.keyNo(),
                        name, command.description(), requestedStatus, current.createdBy(), current.createdAt(),
                        current.updatedAt(), command.lockVersion()));
            } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Test plan", failure); }
        });
    }

    @Override public TestPlan archive(Long actorUserId, Long testPlanId, Integer lockVersion) {
        return archive(actorUserId, null, testPlanId, lockVersion);
    }

    @Override public TestPlan archive(Long actorUserId, Long projectId, Long testPlanId, Integer lockVersion) {
        ServiceValidation.required(testPlanId, "testPlanId");
        ServiceValidation.required(lockVersion, "lockVersion");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan preliminary = daos.testPlans().findById(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            Project project = writableProject(daos, actorUserId, preliminary.projectId());
            ProjectOwnership.require(preliminary.projectId(), projectId);
            TestPlan current = daos.testPlans().findByIdForUpdate(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            ensureSameProject(current.projectId(), project.id(), "Test plan");
            if (current.status() == TestPlanStatus.ARCHIVED) {
                throw new ConflictException("Test plan is already archived");
            }
            try {
                return daos.testPlans().update(new TestPlan(current.id(), current.projectId(), current.keyNo(),
                        current.name(), current.description(), TestPlanStatus.ARCHIVED, current.createdBy(),
                        current.createdAt(), current.updatedAt(), lockVersion));
            } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Test plan", failure); }
        });
    }

    @Override public TestPlanCase addCase(Long actorUserId, Long testPlanId, Long testCaseId,
                                         Integer planLockVersion) {
        return addCase(actorUserId, null, testPlanId, testCaseId, planLockVersion);
    }

    @Override public TestPlanCase addCase(Long actorUserId, Long projectId, Long testPlanId, Long testCaseId, Integer planLockVersion) {
        ServiceValidation.required(testPlanId, "testPlanId");
        ServiceValidation.required(testCaseId, "testCaseId");
        ServiceValidation.required(planLockVersion, "planLockVersion");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan preliminary = daos.testPlans().findById(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            Project project = writableProject(daos, actorUserId, preliminary.projectId());
            ProjectOwnership.require(preliminary.projectId(), projectId);
            TestCase testCase = lockCases(daos, project.id(), List.of(testCaseId), false).getFirst();
            TestPlan current = lockMutablePlan(daos, preliminary.id(), project.id());
            if (lockedPlanCaseIds(daos, current.id()).contains(testCase.id())) {
                throw new ConflictException("Test case is already in the test plan");
            }
            updateScopeVersion(daos, current, planLockVersion);
            return daos.testPlanCases().add(new TestPlanCase(current.id(), testCase.id(), actorUserId, null));
        });
    }

    @Override public void removeCase(Long actorUserId, Long testPlanId, Long testCaseId,
                                    Integer planLockVersion) {
        removeCase(actorUserId, null, testPlanId, testCaseId, planLockVersion);
    }

    @Override public void removeCase(Long actorUserId, Long projectId, Long testPlanId, Long testCaseId, Integer planLockVersion) {
        ServiceValidation.required(testPlanId, "testPlanId");
        ServiceValidation.required(testCaseId, "testCaseId");
        ServiceValidation.required(planLockVersion, "planLockVersion");
        transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan preliminary = daos.testPlans().findById(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            Project project = writableProject(daos, actorUserId, preliminary.projectId());
            ProjectOwnership.require(preliminary.projectId(), projectId);
            TestCase testCase = daos.testCases().findByIdForUpdate(testCaseId)
                    .orElseThrow(() -> new NotFoundException("Test case does not exist"));
            ensureSameProject(testCase.projectId(), project.id(), "Test case");
            TestPlan current = lockMutablePlan(daos, preliminary.id(), project.id());
            if (!lockedPlanCaseIds(daos, current.id()).contains(testCaseId)) {
                throw new NotFoundException("Test case is not in the test plan");
            }
            updateScopeVersion(daos, current, planLockVersion);
            if (!daos.testPlanCases().remove(current.id(), testCaseId)) {
                throw new ConflictException("Test plan scope changed during removal");
            }
            return null;
        });
    }

    @Override public List<TestPlan> listByProject(Long actorUserId, Long projectId) {
        ServiceValidation.required(projectId, "projectId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            daos.projects().findById(projectId).orElseThrow(() -> new NotFoundException("Project does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, projectId);
            return daos.testPlans().listByProject(projectId);
        });
    }

    @Override public TestPlanDetails getDetails(Long actorUserId, Long projectId, Long testPlanId) {
        ServiceValidation.required(projectId, "projectId");
        ServiceValidation.required(testPlanId, "testPlanId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestPlan plan = daos.testPlans().findById(testPlanId)
                    .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, plan.projectId());
            ProjectOwnership.require(plan.projectId(), projectId);
            return new TestPlanDetails(plan, daos.testPlanCases().listByTestPlan(plan.id()));
        });
    }

    private static void updateScopeVersion(ServiceDaos daos, TestPlan current, Integer suppliedVersion) {
        try {
            daos.testPlans().update(new TestPlan(current.id(), current.projectId(), current.keyNo(), current.name(),
                    current.description(), TestPlanStatus.DRAFT, current.createdBy(), current.createdAt(),
                    current.updatedAt(), suppliedVersion));
        } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Test plan", failure); }
    }

    private static TestPlan lockMutablePlan(ServiceDaos daos, Long planId, Long projectId) {
        TestPlan current = daos.testPlans().findByIdForUpdate(planId)
                .orElseThrow(() -> new NotFoundException("Test plan does not exist"));
        ensureSameProject(current.projectId(), projectId, "Test plan");
        if (current.status() == TestPlanStatus.ARCHIVED) {
            throw new ConflictException("Archived test plan is read-only");
        }
        return current;
    }

    private Project writableProject(ServiceDaos daos, Long actorUserId, Long projectId) {
        Project project = daos.projects().findByIdForShare(projectId)
                .orElseThrow(() -> new NotFoundException("Project does not exist"));
        access.requireAssetWrite(daos.users(), daos.members(), actorUserId, project.id());
        if (project.status() != ProjectStatus.ACTIVE) throw new ConflictException("Archived project is read-only");
        return project;
    }

    private static List<Long> distinctCaseIds(List<Long> values, boolean requireNonempty) {
        if (values == null) throw new ValidationException("testCaseIds is required; use an empty list for no initial scope");
        if (requireNonempty && values.isEmpty()) throw new ValidationException("At least one test case is required");
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : values) {
            if (id == null) throw new ValidationException("testCaseId is required");
            if (!ids.add(id)) throw new ValidationException("Duplicate testCaseId is not allowed");
        }
        return List.copyOf(ids);
    }

    private static List<TestCase> lockCases(ServiceDaos daos, Long projectId, List<Long> caseIds,
                                            boolean requireReady) {
        List<Long> sortedIds = caseIds.stream().sorted().toList();
        List<TestCase> cases = new ArrayList<>(sortedIds.size());
        for (Long caseId : sortedIds) {
            TestCase testCase = daos.testCases().findByIdForUpdate(caseId)
                    .orElseThrow(() -> new NotFoundException("Test case does not exist: " + caseId));
            ensureSameProject(testCase.projectId(), projectId, "Test case");
            if (testCase.status() == TestCaseStatus.ARCHIVED) {
                throw new ConflictException("Archived test case cannot be added to a test plan");
            }
            if (requireReady && testCase.status() != TestCaseStatus.READY) {
                throw new ConflictException("READY test plan requires every test case to be READY");
            }
            cases.add(testCase);
        }
        return List.copyOf(cases);
    }

    private static List<Long> planCaseIds(ServiceDaos daos, Long planId) {
        return daos.testPlanCases().listByTestPlan(planId).stream().map(TestPlanCase::testCaseId).sorted().toList();
    }

    private static List<Long> lockedPlanCaseIds(ServiceDaos daos, Long planId) {
        return daos.testPlanCases().listByTestPlanForUpdate(planId).stream()
                .map(TestPlanCase::testCaseId).toList();
    }

    private static void ensureSameProject(Long actual, Long expected, String entity) {
        if (!Objects.equals(actual, expected)) throw new ValidationException(entity + " belongs to another project");
    }
}
