package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.RecordAttemptCommand;
import io.github.lz007001cn.qatrack.service.exception.*;
import io.github.lz007001cn.qatrack.service.support.*;
import java.time.*;
import java.util.*;

public final class DefaultTestExecutionService implements TestExecutionService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daoFactory;
    private final ProjectAccessPolicy access;
    private final Clock clock;

    public DefaultTestExecutionService(ServiceTransaction transactions, ServiceDaoFactory daoFactory,
                                       ProjectAccessPolicy access, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daoFactory = Objects.requireNonNull(daoFactory);
        this.access = Objects.requireNonNull(access);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public TestAttempt recordAttempt(Long actorUserId, RecordAttemptCommand command) {
        validate(command);
        try {
            return appendOnce(actorUserId, command);
        } catch (DataAccessException failure) {
            if (failure.getVendorCode() == 1062) {
                return recoverConcurrentSubmission(actorUserId, command, failure);
            }
            if (failure.getVendorCode() != 1213) throw failure;
            try {
                return appendOnce(actorUserId, command);
            } catch (DataAccessException retryFailure) {
                retryFailure.addSuppressed(failure);
                if (retryFailure.getVendorCode() == 1062) {
                    return recoverConcurrentSubmission(actorUserId, command, retryFailure);
                }
                throw retryFailure;
            }
        }
    }

    private TestAttempt appendOnce(Long actorUserId, RecordAttemptCommand command) {
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestRunCase preliminaryRunCase = daos.runCases().findById(command.runCaseId())
                    .orElseThrow(() -> new NotFoundException("Test run case does not exist"));
            TestRun preliminaryRun = daos.testRuns().findById(preliminaryRunCase.testRunId())
                    .orElseThrow(() -> new NotFoundException("Test run does not exist"));
            Project project = writableProject(daos, actorUserId, preliminaryRun.projectId());
            TestRun run = daos.testRuns().findByIdForUpdate(preliminaryRun.id())
                    .orElseThrow(() -> new NotFoundException("Test run does not exist"));
            if (!Objects.equals(run.projectId(), project.id())) {
                throw new ConflictException("Test run project changed during attempt submission");
            }
            TestRunCase runCase = daos.runCases().findByIdForUpdate(command.runCaseId())
                    .orElseThrow(() -> new NotFoundException("Test run case does not exist"));
            if (!Objects.equals(runCase.testRunId(), run.id())) {
                throw new ConflictException("Test run case moved during attempt submission");
            }
            Optional<TestAttempt> duplicate = daos.attempts().findBySubmissionKey(command.submissionKey());
            if (duplicate.isPresent()) return acceptIdempotentRetry(duplicate.get(), actorUserId, command);
            if (run.status() != TestRunStatus.IN_PROGRESS) {
                throw new ConflictException("Attempts can only be appended to an IN_PROGRESS test run");
            }
            int nextAttempt = daos.attempts().findLatestByRunCaseForUpdate(runCase.id())
                    .map(TestAttempt::attemptNo).map(DefaultTestExecutionService::incrementAttempt).orElse(1);
            return daos.attempts().insert(new TestAttempt(null, runCase.id(), nextAttempt, command.status(),
                    actorUserId, null, null, LocalDateTime.now(clock), null, command.durationMs(),
                    command.comment(), command.failureMessage(), command.submissionKey()));
        });
    }

    private TestAttempt recoverConcurrentSubmission(Long actorUserId, RecordAttemptCommand command,
                                                     DataAccessException originalFailure) {
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestRunCase runCase = daos.runCases().findById(command.runCaseId())
                    .orElseThrow(() -> new NotFoundException("Test run case does not exist"));
            TestRun run = daos.testRuns().findById(runCase.testRunId())
                    .orElseThrow(() -> new NotFoundException("Test run does not exist"));
            writableProject(daos, actorUserId, run.projectId());
            TestAttempt existing = daos.attempts().findBySubmissionKey(command.submissionKey())
                    .orElseThrow(() -> originalFailure);
            return acceptIdempotentRetry(existing, actorUserId, command);
        });
    }

    @Override public List<TestAttempt> listAttempts(Long actorUserId, Long runCaseId) {
        ServiceValidation.required(runCaseId, "runCaseId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestRunCase runCase = daos.runCases().findById(runCaseId)
                    .orElseThrow(() -> new NotFoundException("Test run case does not exist"));
            TestRun run = daos.testRuns().findById(runCase.testRunId())
                    .orElseThrow(() -> new NotFoundException("Test run does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, run.projectId());
            return daos.attempts().listByRunCase(runCase.id());
        });
    }

    @Override public Optional<TestAttemptStatus> currentOutcome(Long actorUserId, Long runCaseId) {
        ServiceValidation.required(runCaseId, "runCaseId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestRunCase runCase = daos.runCases().findById(runCaseId)
                    .orElseThrow(() -> new NotFoundException("Test run case does not exist"));
            TestRun run = daos.testRuns().findById(runCase.testRunId())
                    .orElseThrow(() -> new NotFoundException("Test run does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, run.projectId());
            return daos.attempts().findLatestByRunCase(runCase.id()).map(TestAttempt::status);
        });
    }

    private Project writableProject(ServiceDaos daos, Long actorUserId, Long projectId) {
        Project project = daos.projects().findByIdForShare(projectId)
                .orElseThrow(() -> new NotFoundException("Project does not exist"));
        access.requireAssetWrite(daos.users(), daos.members(), actorUserId, project.id());
        if (project.status() != ProjectStatus.ACTIVE) throw new ConflictException("Archived project is read-only");
        return project;
    }

    private static TestAttempt acceptIdempotentRetry(TestAttempt existing, Long actorUserId,
                                                     RecordAttemptCommand command) {
        boolean samePayload = Objects.equals(existing.testRunCaseId(), command.runCaseId())
                && existing.status() == command.status()
                && Objects.equals(existing.executedBy(), actorUserId)
                && existing.importId() == null && existing.automationMappingId() == null
                && Objects.equals(existing.durationMs(), command.durationMs())
                && Objects.equals(existing.comment(), command.comment())
                && Objects.equals(existing.failureMessage(), command.failureMessage());
        if (!samePayload) throw new ConflictException("Submission key was already used with different attempt data");
        return existing;
    }

    private static int incrementAttempt(Integer value) {
        if (value == null || value <= 0 || value == Integer.MAX_VALUE) {
            throw new ConflictException("Attempt sequence is exhausted or invalid");
        }
        return value + 1;
    }

    private static void validate(RecordAttemptCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.runCaseId(), "runCaseId");
        ServiceValidation.required(command.status(), "status");
        ServiceValidation.required(command.submissionKey(), "submissionKey");
        if (command.durationMs() != null && command.durationMs() < 0) {
            throw new ValidationException("durationMs must be non-negative");
        }
        if (command.status() != TestAttemptStatus.FAIL && command.failureMessage() != null) {
            throw new ValidationException("failureMessage is only valid for FAIL attempts");
        }
    }
}
