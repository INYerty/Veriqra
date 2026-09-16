package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.exception.OptimisticLockException;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.exception.*;
import io.github.lz007001cn.qatrack.service.support.*;
import java.util.*;

public final class DefaultTestCaseService implements TestCaseService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daoFactory;
    private final ProjectAccessPolicy access;

    public DefaultTestCaseService(ServiceTransaction transactions, ServiceDaoFactory daoFactory,
                                  ProjectAccessPolicy access) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daoFactory = Objects.requireNonNull(daoFactory);
        this.access = Objects.requireNonNull(access);
    }

    @Override public TestCase create(Long actorUserId, CreateTestCaseCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.projectId(), "projectId");
        String title = ServiceValidation.requiredText(command.title(), 240, "title");
        Priority priority = ServiceValidation.required(command.priority(), "priority");
        List<TestStepInput> steps = ServiceValidation.steps(command.steps());
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Project project = writableProject(daos, actorUserId, command.projectId());
            long key = daos.counters().allocateNext(project.id(), CounterEntityType.TC);
            TestCase saved = daos.testCases().insert(new TestCase(null, project.id(), key, title,
                    command.description(), command.preconditions(), priority, TestCaseStatus.DRAFT,
                    actorUserId, null, null, null));
            for (TestStepInput step : steps) daos.steps().add(toStep(saved.id(), step));
            return saved;
        });
    }

    @Override public TestCase get(Long actorUserId, Long testCaseId) {
        ServiceValidation.required(testCaseId, "testCaseId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestCase value = daos.testCases().findById(testCaseId)
                    .orElseThrow(() -> new NotFoundException("Test case does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, value.projectId());
            return value;
        });
    }

    @Override public List<TestCase> listByProject(Long actorUserId, Long projectId) {
        ServiceValidation.required(projectId, "projectId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            daos.projects().findById(projectId).orElseThrow(() -> new NotFoundException("Project does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, projectId);
            return daos.testCases().listByProject(projectId);
        });
    }

    @Override public List<TestStep> listSteps(Long actorUserId, Long testCaseId) {
        ServiceValidation.required(testCaseId, "testCaseId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestCase value = daos.testCases().findById(testCaseId)
                    .orElseThrow(() -> new NotFoundException("Test case does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, value.projectId());
            return daos.steps().listByTestCase(value.id());
        });
    }

    @Override public TestCase update(Long actorUserId, UpdateTestCaseCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.testCaseId(), "testCaseId");
        ServiceValidation.required(command.lockVersion(), "lockVersion");
        String title = ServiceValidation.requiredText(command.title(), 240, "title");
        Priority priority = ServiceValidation.required(command.priority(), "priority");
        TestCaseStatus requestedStatus = ServiceValidation.required(command.status(), "status");
        List<TestStepInput> requestedSteps = ServiceValidation.steps(command.steps());
        if (requestedStatus == TestCaseStatus.READY && requestedSteps.isEmpty()) {
            throw new ValidationException("READY test case requires at least one step");
        }
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestCase preliminary = daos.testCases().findById(command.testCaseId())
                    .orElseThrow(() -> new NotFoundException("Test case does not exist"));
            writableProject(daos, actorUserId, preliminary.projectId());
            TestCase current = daos.testCases().findByIdForUpdate(command.testCaseId())
                    .orElseThrow(() -> new NotFoundException("Test case does not exist"));
            if (!Objects.equals(current.projectId(), preliminary.projectId())) {
                throw new ConflictException("Test case project changed during update");
            }
            if (current.status() == TestCaseStatus.ARCHIVED) throw new ConflictException("Archived test case is read-only");
            List<TestStep> currentSteps = daos.steps().listByTestCase(current.id());
            boolean stepsChanged = !sameSteps(currentSteps, requestedSteps);
            boolean semanticChange = stepsChanged || !Objects.equals(current.title(), title)
                    || !Objects.equals(current.description(), command.description())
                    || !Objects.equals(current.preconditions(), command.preconditions());
            TestCaseStatus persistedStatus = semanticChange && requestedStatus != TestCaseStatus.ARCHIVED
                    ? TestCaseStatus.DRAFT : requestedStatus;
            TestCase updated;
            try {
                updated = daos.testCases().update(new TestCase(current.id(), current.projectId(), current.keyNo(),
                        title, command.description(), command.preconditions(), priority, persistedStatus,
                        current.createdBy(), current.createdAt(), current.updatedAt(), command.lockVersion()));
            } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Test case", failure); }
            if (stepsChanged) {
                daos.steps().deleteByTestCase(current.id());
                for (TestStepInput step : requestedSteps) daos.steps().add(toStep(current.id(), step));
            }
            if (semanticChange) daos.traceability().markConfirmedNeedsReviewByTestCase(current.id());
            return updated;
        });
    }

    private Project writableProject(ServiceDaos daos, Long actorUserId, Long projectId) {
        Project project = daos.projects().findByIdForShare(projectId)
                .orElseThrow(() -> new NotFoundException("Project does not exist"));
        access.requireAssetWrite(daos.users(), daos.members(), actorUserId, project.id());
        if (project.status() != ProjectStatus.ACTIVE) throw new ConflictException("Archived project is read-only");
        return project;
    }

    private static TestStep toStep(Long caseId, TestStepInput input) {
        return new TestStep(caseId, input.stepOrder(), input.action(), input.expectedResult());
    }

    private static boolean sameSteps(List<TestStep> current, List<TestStepInput> requested) {
        if (current.size() != requested.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            TestStep saved = current.get(i); TestStepInput input = requested.get(i);
            if (!Objects.equals(saved.stepOrder(), input.stepOrder()) || !Objects.equals(saved.action(), input.action())
                    || !Objects.equals(saved.expectedResult(), input.expectedResult())) return false;
        }
        return true;
    }
}
