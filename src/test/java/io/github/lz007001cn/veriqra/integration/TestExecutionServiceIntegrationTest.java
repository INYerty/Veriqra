package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.DefaultTestExecutionService;
import io.github.lz007001cn.veriqra.service.TestExecutionService;
import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TestExecutionServiceIntegrationTest extends ServiceFixture {
    @Test void attemptsAreAppendOnlyIdempotentAndNotRunIsAbsence() {
        RunContext context = runWithCases("ATTEMPTS", 1);
        TestRunCase runCase = context.runCases().getFirst();
        assertEquals(Optional.empty(), execution.currentOutcome(context.tester().id(), runCase.id()));
        UUID failKey = UUID.randomUUID();
        RecordAttemptCommand fail = new RecordAttemptCommand(runCase.id(), TestAttemptStatus.FAIL,
                25L, "first", "failure", failKey);
        TestAttempt first = execution.recordAttempt(context.tester().id(), fail);
        TestAttempt retry = execution.recordAttempt(context.tester().id(), fail);
        assertEquals(first.id(), retry.id());
        assertThrows(ConflictException.class, () -> execution.recordAttempt(context.tester().id(),
                new RecordAttemptCommand(runCase.id(), TestAttemptStatus.PASS, 25L, "changed", null, failKey)));
        TestAttempt second = execution.recordAttempt(context.tester().id(), new RecordAttemptCommand(
                runCase.id(), TestAttemptStatus.PASS, 20L, "retest", null, UUID.randomUUID()));
        TestAttempt third = execution.recordAttempt(context.tester().id(), new RecordAttemptCommand(
                runCase.id(), TestAttemptStatus.BLOCKED, null, null, null, UUID.randomUUID()));
        TestAttempt fourth = execution.recordAttempt(context.tester().id(), new RecordAttemptCommand(
                runCase.id(), TestAttemptStatus.SKIPPED, null, null, null, UUID.randomUUID()));
        assertEquals(List.of(1, 2, 3, 4), List.of(first.attemptNo(), second.attemptNo(),
                third.attemptNo(), fourth.attemptNo()));
        assertEquals(List.of(TestAttemptStatus.FAIL, TestAttemptStatus.PASS, TestAttemptStatus.BLOCKED,
                        TestAttemptStatus.SKIPPED), execution.listAttempts(context.tester().id(), runCase.id())
                .stream().map(TestAttempt::status).toList());
        assertEquals(Optional.of(TestAttemptStatus.SKIPPED),
                execution.currentOutcome(context.tester().id(), runCase.id()));
    }

    @Test void completeRequiresEveryRunCaseHandledAndTerminalRunRejectsAttemptsOrReopen() {
        RunContext context = runWithCases("COMPLETE", 2);
        assertThrows(ConflictException.class, () -> testRuns.complete(context.tester().id(),
                context.run().id(), context.run().lockVersion()));
        RecordAttemptCommand firstCommand = new RecordAttemptCommand(context.runCases().get(0).id(),
                TestAttemptStatus.BLOCKED, null, null, null, UUID.randomUUID());
        TestAttempt first = execution.recordAttempt(context.tester().id(), firstCommand);
        assertThrows(ConflictException.class, () -> testRuns.complete(context.tester().id(),
                context.run().id(), context.run().lockVersion()));
        execution.recordAttempt(context.tester().id(), new RecordAttemptCommand(context.runCases().get(1).id(),
                TestAttemptStatus.SKIPPED, null, null, null, UUID.randomUUID()));
        TestRun completed = testRuns.complete(context.tester().id(), context.run().id(), context.run().lockVersion());
        assertEquals(TestRunStatus.COMPLETED, completed.status());
        assertNotNull(completed.endedAt());
        assertEquals(first.id(), execution.recordAttempt(context.tester().id(), firstCommand).id());
        assertThrows(ConflictException.class, () -> execution.recordAttempt(context.tester().id(),
                new RecordAttemptCommand(context.runCases().getFirst().id(), TestAttemptStatus.PASS,
                        1L, null, null, UUID.randomUUID())));
        assertThrows(ConflictException.class,
                () -> testRuns.cancel(context.tester().id(), completed.id(), completed.lockVersion()));
    }

    @Test void concurrentAttemptsReceiveOneContiguousSequence() throws Exception {
        RunContext context = runWithCases("ATTEMPTCON", 1);
        Long runCaseId = context.runCases().getFirst().id();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<TestAttempt>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> execution.recordAttempt(context.tester().id(), new RecordAttemptCommand(
                        runCaseId, TestAttemptStatus.PASS, 1L, null, null, UUID.randomUUID())));
            }
            for (Future<TestAttempt> future : executor.invokeAll(tasks)) future.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList(),
                execution.listAttempts(context.tester().id(), runCaseId).stream()
                        .map(TestAttempt::attemptNo).toList());
    }

    @Test void concurrentSameSubmissionKeyAcrossRunCasesProducesBusinessConflict() {
        RunContext firstContext = runWithCases("KEYCONONE", 1);
        RunContext secondContext = runWithCases("KEYCONTWO", 1);
        CountDownLatch atInsert = new CountDownLatch(2);
        TestExecutionService concurrent = barrierExecution(atInsert);
        UUID sharedKey = UUID.randomUUID();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<TestAttempt> first = executor.submit(() -> concurrent.recordAttempt(
                        firstContext.tester().id(), new RecordAttemptCommand(
                                firstContext.runCases().getFirst().id(), TestAttemptStatus.PASS,
                                1L, null, null, sharedKey)));
                Future<TestAttempt> second = executor.submit(() -> concurrent.recordAttempt(
                        secondContext.tester().id(), new RecordAttemptCommand(
                                secondContext.runCases().getFirst().id(), TestAttemptStatus.PASS,
                                1L, null, null, sharedKey)));
                int accepted = 0;
                int conflicts = 0;
                for (Future<TestAttempt> future : List.of(first, second)) {
                    try {
                        future.get();
                        accepted++;
                    } catch (ExecutionException failure) {
                        if (!(failure.getCause() instanceof ConflictException)) {
                            throw new AssertionError("Expected a business conflict", failure.getCause());
                        }
                        conflicts++;
                    }
                }
                assertEquals(1, accepted);
                assertEquals(1, conflicts);
            }
        });
        long rows = execution.listAttempts(firstContext.tester().id(), firstContext.runCases().getFirst().id()).size()
                + execution.listAttempts(secondContext.tester().id(), secondContext.runCases().getFirst().id()).size();
        assertEquals(1, rows);
    }

    @Test void concurrentSameSubmissionKeyAndPayloadReturnsOneAttempt() {
        RunContext context = runWithCases("KEYSAME", 1);
        RecordAttemptCommand command = new RecordAttemptCommand(context.runCases().getFirst().id(),
                TestAttemptStatus.PASS, 1L, "same", null, UUID.randomUUID());
        CountDownLatch start = new CountDownLatch(1);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Callable<TestAttempt> submit = () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return execution.recordAttempt(context.tester().id(), command);
                };
                Future<TestAttempt> first = executor.submit(submit);
                Future<TestAttempt> second = executor.submit(submit);
                start.countDown();
                assertEquals(first.get().id(), second.get().id());
            }
        });
        assertEquals(1, execution.listAttempts(context.tester().id(), command.runCaseId()).size());
    }

    @Test void concurrentFirstAttemptsAcrossRunCasesSurviveEmptyIndexGapDeadlock() {
        RunContext firstContext = runWithCases("GAPCONONE", 1);
        RunContext secondContext = runWithCases("GAPCONTWO", 1);
        TestExecutionService concurrent = barrierExecution(new CountDownLatch(2));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<TestAttempt> first = executor.submit(() -> concurrent.recordAttempt(
                        firstContext.tester().id(), new RecordAttemptCommand(
                                firstContext.runCases().getFirst().id(), TestAttemptStatus.PASS,
                                1L, null, null, UUID.randomUUID())));
                Future<TestAttempt> second = executor.submit(() -> concurrent.recordAttempt(
                        secondContext.tester().id(), new RecordAttemptCommand(
                                secondContext.runCases().getFirst().id(), TestAttemptStatus.PASS,
                                1L, null, null, UUID.randomUUID())));
                assertEquals(1, first.get().attemptNo());
                assertEquals(1, second.get().attemptNo());
            }
        });
    }

    @Test void deadlockRetryRunsWholeTransactionAtMostOnce() {
        RunContext context = runWithCases("RETRYONCE", 1);
        AtomicInteger inserts = new AtomicInteger();
        var failingFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var daos = jdbcDaos.create(connection);
            var attempts = new ServiceDaoDelegates.AttemptDelegate(daos.attempts()) {
                @Override public TestAttempt insert(TestAttempt value) {
                    inserts.incrementAndGet();
                    throw new DataAccessException("Injected deadlock", new SQLException(
                            "Deadlock found when trying to get lock", "40001", 1213));
                }
            };
            return ServiceDaoDelegates.attempts(daos, attempts);
        };
        TestExecutionService failing = new DefaultTestExecutionService(
                serviceTx, failingFactory, access, executionClock);
        DataAccessException failure = assertThrows(DataAccessException.class, () -> failing.recordAttempt(
                context.tester().id(), new RecordAttemptCommand(context.runCases().getFirst().id(),
                        TestAttemptStatus.PASS, 1L, null, null, UUID.randomUUID())));
        assertEquals(1213, failure.getVendorCode());
        assertEquals(2, inserts.get());
        assertTrue(execution.listAttempts(context.tester().id(), context.runCases().getFirst().id()).isEmpty());
    }

    @Test void failedAttemptInsertRollsBackAndNextAcceptedAttemptIsStillOne() {
        RunContext context = runWithCases("ATTEMPTFAIL", 1);
        Long runCaseId = context.runCases().getFirst().id();
        var failingFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var failing = new ServiceDaoDelegates.AttemptDelegate(d.attempts()) {
                @Override public TestAttempt insert(TestAttempt value) {
                    target.insert(value);
                    throw new DataAccessException("Injected failure after attempt insert");
                }
            };
            return ServiceDaoDelegates.attempts(d, failing);
        };
        var failingService = new DefaultTestExecutionService(serviceTx, failingFactory, access, executionClock);
        UUID key = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> failingService.recordAttempt(context.tester().id(),
                new RecordAttemptCommand(runCaseId, TestAttemptStatus.PASS, 1L, null, null, key)));
        assertTrue(execution.listAttempts(context.tester().id(), runCaseId).isEmpty());
        TestAttempt accepted = execution.recordAttempt(context.tester().id(),
                new RecordAttemptCommand(runCaseId, TestAttemptStatus.PASS, 1L, null, null, key));
        assertEquals(1, accepted.attemptNo());
    }

    @Test void developerInactiveMemberAndDisabledActorCannotRecordAttempts() {
        RunContext context = runWithCases("ATTEMPTAUTH", 1);
        User developer = actor("developer-attempt", SystemRole.USER, UserStatus.ACTIVE);
        addMember(context.project(), developer, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        RecordAttemptCommand command = new RecordAttemptCommand(context.runCases().getFirst().id(),
                TestAttemptStatus.PASS, 1L, null, null, UUID.randomUUID());
        assertThrows(ForbiddenException.class, () -> execution.recordAttempt(developer.id(), command));
        tx.inTransaction(c -> {
            var memberDao = new io.github.lz007001cn.veriqra.dao.jdbc.JdbcProjectMemberDao(c);
            ProjectMember member = memberDao.find(context.project().id(), context.tester().id()).orElseThrow();
            memberDao.update(new ProjectMember(member.projectId(), member.userId(), member.projectRole(),
                    MembershipStatus.INACTIVE, member.joinedAt(), member.updatedAt(), member.lockVersion()));
            return null;
        });
        assertThrows(ForbiddenException.class, () -> execution.recordAttempt(context.tester().id(), command));
        tx.inTransaction(c -> {
            var userDao = new io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao(c);
            User admin = userDao.findById(context.admin().id()).orElseThrow();
            userDao.update(new User(admin.id(), admin.username(), admin.displayName(), admin.passwordHash(),
                    admin.systemRole(), UserStatus.DISABLED, admin.createdAt(), admin.updatedAt(), admin.lockVersion()));
            return null;
        });
        assertThrows(ForbiddenException.class, () -> execution.recordAttempt(context.admin().id(), command));
    }

    private RunContext runWithCases(String key, int count) {
        User admin = actor("admin-" + key.toLowerCase(Locale.ROOT), SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-" + key.toLowerCase(Locale.ROOT), SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, key, tester, ProjectRole.TESTER);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) ids.add(readyCase(tester, project, "Case " + i, 1).id());
        TestRun run = testRuns.createAdHoc(tester.id(),
                new CreateAdHocRunCommand(project.id(), "Run", null, null, ids));
        return new RunContext(admin, tester, project, run, testRuns.listRunCases(tester.id(), run.id()));
    }

    private TestExecutionService barrierExecution(CountDownLatch atInsert) {
        var barrierFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var daos = jdbcDaos.create(connection);
            var attempts = new ServiceDaoDelegates.AttemptDelegate(daos.attempts()) {
                @Override public TestAttempt insert(TestAttempt value) {
                    atInsert.countDown();
                    try {
                        if (!atInsert.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Both submissions must reach the attempt insert");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while coordinating attempt submission", failure);
                    }
                    return super.insert(value);
                }
            };
            return ServiceDaoDelegates.attempts(daos, attempts);
        };
        return new DefaultTestExecutionService(serviceTx, barrierFactory, access, executionClock);
    }

    private record RunContext(User admin, User tester, Project project, TestRun run,
                              List<TestRunCase> runCases) { }
}
