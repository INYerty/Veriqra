package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.admin.*;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcCollaborationDao;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcTaskCreditDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.service.DefaultCollaborationService;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the fixture-owned MySQL 8.0.46 schema on the explicitly configured test port. */
class TaskCreditWorkflowIntegrationTest extends ServiceFixture {
    private User admin, manager, worker, recipient, outsider;
    private Project project, other;
    private ProjectTeam team;
    private CreditService credits;

    @BeforeEach void setupWorkflow() {
        admin = actor("credit_admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        manager = actor("credit_manager", SystemRole.USER, UserStatus.ACTIVE);
        worker = actor("credit_worker", SystemRole.USER, UserStatus.ACTIVE);
        recipient = actor("credit_recipient", SystemRole.USER, UserStatus.ACTIVE);
        outsider = actor("credit_outsider", SystemRole.USER, UserStatus.ACTIVE);
        project = createProject(admin, "CREDIT", manager, ProjectRole.TESTER);
        other = createProject(admin, "OTHER", outsider, ProjectRole.TESTER);
        addMember(project, worker, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(project, recipient, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        collaboration.appointManager(admin.id(), project.id(), manager.id());
        team = collaboration.createTeam(manager.id(), project.id(), "Delivery", manager.id());
        collaboration.setTeamMember(manager.id(), project.id(), team.id(), worker.id(), MembershipStatus.ACTIVE);
        collaboration.setTeamMember(manager.id(), project.id(), team.id(), recipient.id(), MembershipStatus.ACTIVE);
        tx.inTransaction(c -> {
            var accounts = new JdbcCreditDao(c);
            for (User user : List.of(admin, manager, worker, recipient, outsider)) accounts.createAccount(user.id());
            return null;
        });
        credits = new CreditService(serviceTx, new AdminAccessPolicy());
    }
    private void grant(User user, long amount) {
        credits.grant(new AdminContext(admin.id(), "127.0.0.1", UUID.randomUUID().toString()),
                user.id(), amount, "fixture");
    }
    private long balance(User user) {
        return credits.account(admin.id(), user.id()).balance();
    }
    private long count(String table, String condition, long id) {
        return tx.inTransaction(c -> {
            try (var s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + condition + "=?")) {
                s.setLong(1, id);
                try (var rs = s.executeQuery()) { rs.next(); return rs.getLong(1); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Test void peerTransferIsAtomicScopedIdempotentAndUsesOneSharedReference() {
        grant(worker, 100);
        String key = UUID.randomUUID().toString();
        CreditTransfer first = collaboration.transferCredit(worker.id(), project.id(), recipient.id(), 7L, "thank you", key);
        assertEquals(key, first.id());
        assertEquals(first, collaboration.transferCredit(worker.id(), project.id(), recipient.id(), 7L, "thank you", key));
        assertEquals(93, balance(worker)); assertEquals(7, balance(recipient));
        assertEquals(2L, (long) tx.inTransaction(c -> {
            try (var s = c.prepareStatement("SELECT COUNT(*) FROM credit_transactions WHERE transfer_id=?")) {
                s.setString(1, key); try (var rs = s.executeQuery()) { rs.next(); return rs.getInt(1); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }));
        assertThrows(ConflictException.class, () -> collaboration.transferCredit(worker.id(), project.id(), recipient.id(), 8L, "thank you", key));
        assertThrows(ValidationException.class, () -> collaboration.transferCredit(worker.id(), project.id(), worker.id(), 1L, null, UUID.randomUUID().toString()));
        assertThrows(ForbiddenException.class, () -> collaboration.transferCredit(worker.id(), project.id(), outsider.id(), 1L, null, UUID.randomUUID().toString()));
        assertThrows(ConflictException.class, () -> collaboration.transferCredit(worker.id(), project.id(), recipient.id(), 94L, null, UUID.randomUUID().toString()));
        assertEquals(93, balance(worker)); assertEquals(7, balance(recipient));
        assertEquals(1, collaboration.myCredits(worker.id()).transactions().stream().filter(e -> key.equals(e.transferId())).count());
        assertThrows(ForbiddenException.class, () -> collaboration.monthlyContribution(outsider.id(), project.id(), "2030-01"));
    }

    @Test void acceptedTaskAwardsRewardAndOneNontransferableContributionOnlyOnce() {
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Deliver", null, worker.id(), 20L);
        assertEquals(20, task.rewardCredit());
        task = collaboration.setTaskReward(manager.id(), project.id(), task.id(), 25L, task.lockVersion());
        WorkTask started = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        final WorkTask immutable = started;
        assertThrows(ConflictException.class, () -> collaboration.setTaskReward(manager.id(), project.id(), immutable.id(), 50L, immutable.lockVersion()));
        WorkTask submitted = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
        assertEquals(0, balance(worker));
        assertEquals(0, collaboration.monthlyContribution(worker.id(), project.id(), "2030-01").stream()
                .filter(row -> row.userId().equals(worker.id())).findFirst().orElseThrow().score());
        WorkTask accepted = collaboration.transitionTask(manager.id(), project.id(), task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion());
        assertEquals(25, balance(worker));
        assertEquals(1, count("contribution_events", "task_id", task.id()));
        assertEquals(1, count("credit_transactions", "task_id", task.id()));
        assertEquals(1, collaboration.monthlyContribution(worker.id(), project.id(), "2030-01").getFirst().score());
        assertThrows(ConflictException.class, () -> collaboration.transitionTask(manager.id(), project.id(), submitted.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()));
        assertEquals(WorkTaskStatus.ACCEPTED, accepted.status());
        assertEquals(25, balance(worker));
    }

    @Test void handoffPaysOnlyOnAcceptanceAndRewardBelongsToFinalWorker() {
        grant(worker, 10);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Handoff", null, worker.id(), 20L);
        String operation = UUID.randomUUID().toString();
        TaskHandoffOffer offer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 5L, "help", operation);
        assertEquals(offer, collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 5L, "help", operation));
        assertEquals(10, balance(worker)); assertEquals(0, balance(recipient));
        TaskHandoffOffer acceptedOffer = collaboration.acceptHandoff(recipient.id(), project.id(), offer.id());
        assertEquals("ACCEPTED", acceptedOffer.status());
        assertEquals(5, balance(worker)); assertEquals(5, balance(recipient));
        assertEquals(recipient.id(), collaboration.getTask(recipient.id(), project.id(), task.id()).task().assigneeUserId());
        assertEquals(1, collaboration.getTask(recipient.id(), project.id(), task.id()).events().stream()
                .filter(e -> e.eventType() == WorkTaskEventType.HANDOFF_ACCEPTED).count());
        assertThrows(ConflictException.class, () -> collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()));
        WorkTask current = collaboration.getTask(recipient.id(), project.id(), task.id()).task();
        WorkTask started = collaboration.transitionTask(recipient.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, current.lockVersion());
        WorkTask submitted = collaboration.transitionTask(recipient.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
        collaboration.transitionTask(manager.id(), project.id(), task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion());
        assertEquals(25, balance(recipient)); assertEquals(5, balance(worker));
        assertEquals(recipient.id(), collaboration.monthlyContribution(recipient.id(), project.id(), "2030-01").getFirst().userId());
    }

    @Test void insufficientAtAcceptKeepsOfferPendingAndLeavesTaskAndLedgerUntouched() {
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "No funds", null, worker.id(), 0L);
        TaskHandoffOffer offer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 5L, null, UUID.randomUUID().toString());
        assertThrows(ConflictException.class, () -> collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()));
        assertEquals("PENDING", collaboration.listHandoffs(worker.id(), project.id()).getFirst().status());
        assertEquals(worker.id(), collaboration.getTask(worker.id(), project.id(), task.id()).task().assigneeUserId());
        assertEquals(0, count("credit_transfers", "offer_id", offer.id()));
        assertEquals("DECLINED", collaboration.declineHandoff(recipient.id(), project.id(), offer.id()).status());
        assertThrows(ConflictException.class, () -> collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()));
        TaskHandoffOffer otherOffer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 3L, null, UUID.randomUUID().toString());
        assertEquals("CANCELLED", collaboration.cancelHandoff(worker.id(), project.id(), otherOffer.id()).status());
    }

    @Test void concurrentDoubleAcceptMovesCreditAndTaskAtMostOnce() throws Exception {
        grant(worker, 8);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Race", null, worker.id(), 0L);
        TaskHandoffOffer offer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 4L, null, UUID.randomUUID().toString());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()); });
            var second = executor.submit(() -> { start.await(); return collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()); });
            start.countDown();
            int success = 0;
            for (var future : List.of(first, second)) {
                try { assertEquals("ACCEPTED", future.get(15, TimeUnit.SECONDS).status()); success++; }
                catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof ConflictException); }
            }
            assertEquals(1, success);
        }
        assertEquals(4, balance(worker)); assertEquals(4, balance(recipient));
        assertEquals(1, count("credit_transfers", "offer_id", offer.id()));
    }

    @Test void concurrentTransfersCannotSpendTheSameBalanceTwice() throws Exception {
        grant(worker, 10);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return collaboration.transferCredit(worker.id(), project.id(),
                    recipient.id(), 8L, null, UUID.randomUUID().toString()); });
            var second = executor.submit(() -> { start.await(); return collaboration.transferCredit(worker.id(), project.id(),
                    manager.id(), 8L, null, UUID.randomUUID().toString()); });
            start.countDown(); int success = 0;
            for (var future : List.of(first, second)) {
                try { future.get(15, TimeUnit.SECONDS); success++; }
                catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof ConflictException); }
            }
            assertEquals(1, success);
        }
        assertEquals(2, balance(worker));
        assertEquals(8, balance(manager) + balance(recipient));
        assertEquals(1, collaboration.myCredits(worker.id()).transactions().stream()
                .filter(e -> e.type().equals("PEER_TRANSFER_OUT")).count());
    }

    @Test void oversizedCreditsDoNotRoundAndRecipientOverflowRollsBack() {
        long overJsSafeInteger = 9_007_199_254_740_993L;
        grant(worker, overJsSafeInteger);
        String key = UUID.randomUUID().toString();
        CreditTransfer transfer = collaboration.transferCredit(worker.id(), project.id(), recipient.id(),
                overJsSafeInteger, null, key);
        assertEquals(overJsSafeInteger, transfer.amount());
        assertEquals(Long.toString(overJsSafeInteger), collaboration.myCredits(recipient.id()).balance());
        assertEquals(0, balance(worker));
        grant(worker, 1);
        grant(recipient, Long.MAX_VALUE - overJsSafeInteger);
        assertEquals(Long.MAX_VALUE, balance(recipient));
        assertThrows(ConflictException.class, () -> collaboration.transferCredit(worker.id(), project.id(),
                recipient.id(), 1L, null, UUID.randomUUID().toString()));
        assertEquals(1, balance(worker)); assertEquals(Long.MAX_VALUE, balance(recipient));
    }

    @Test void concurrentReviewersCannotRewardTwice() throws Exception {
        User secondReviewer = actor("credit_second_reviewer", SystemRole.USER, UserStatus.ACTIVE);
        addMember(project, secondReviewer, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        collaboration.appointManager(admin.id(), project.id(), secondReviewer.id());
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Review race", null, worker.id(), 9L);
        WorkTask started = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        WorkTask submitted = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return collaboration.transitionTask(manager.id(), project.id(),
                    task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()); });
            var second = executor.submit(() -> { start.await(); return collaboration.transitionTask(secondReviewer.id(), project.id(),
                    task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()); });
            start.countDown(); int success = 0;
            for (var future : List.of(first, second)) {
                try { future.get(15, TimeUnit.SECONDS); success++; }
                catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof ConflictException); }
            }
            assertEquals(1, success);
        }
        assertEquals(9, balance(worker));
        assertEquals(1, count("credit_transactions", "task_id", task.id()));
        assertEquals(1, count("contribution_events", "task_id", task.id()));
    }

    @Test void disabledAndForeignRecipientsCannotReceiveCreditOrHandoff() {
        User disabled = actor("credit_disabled", SystemRole.USER, UserStatus.DISABLED);
        addMember(project, disabled, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        tx.inTransaction(c -> { new JdbcCreditDao(c).createAccount(disabled.id());
            new JdbcCollaborationDao(c).setTeamMember(team.id(), project.id(), disabled.id(), MembershipStatus.ACTIVE);
            return null; });
        grant(worker, 10);
        assertThrows(ForbiddenException.class, () -> collaboration.transferCredit(worker.id(), project.id(),
                disabled.id(), 1L, null, UUID.randomUUID().toString()));
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Scope", null, worker.id(), 0L);
        assertThrows(ForbiddenException.class, () -> collaboration.offerHandoff(worker.id(), project.id(), task.id(),
                disabled.id(), 1L, null, UUID.randomUUID().toString()));
        assertThrows(ForbiddenException.class, () -> collaboration.offerHandoff(worker.id(), project.id(), task.id(),
                outsider.id(), 1L, null, UUID.randomUUID().toString()));
        assertThrows(NotFoundException.class, () -> collaboration.offerHandoff(outsider.id(), other.id(), task.id(),
                worker.id(), 1L, null, UUID.randomUUID().toString()));
        TaskHandoffOffer valid = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(),
                1L, null, UUID.randomUUID().toString());
        assertThrows(NotFoundException.class, () -> collaboration.acceptHandoff(outsider.id(), other.id(), valid.id()));
        assertThrows(ForbiddenException.class, () -> collaboration.listHandoffs(worker.id(), other.id()));
        assertEquals(10, balance(worker));
    }

    @Test void rewardAndOutgoingTransferSerializeOnOneAccountWithoutLostUpdate() throws Exception {
        grant(worker, 1);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Parallel reward", null, worker.id(), 5L);
        WorkTask started = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        WorkTask submitted = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var acceptance = executor.submit(() -> { start.await(); return collaboration.transitionTask(manager.id(), project.id(),
                    task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()); });
            var transfer = executor.submit(() -> { start.await(); return collaboration.transferCredit(worker.id(), project.id(),
                    recipient.id(), 1L, null, UUID.randomUUID().toString()); });
            start.countDown();
            assertEquals(WorkTaskStatus.ACCEPTED, acceptance.get(15, TimeUnit.SECONDS).status());
            assertEquals(1L, transfer.get(15, TimeUnit.SECONDS).amount());
        }
        assertEquals(5, balance(worker)); assertEquals(1, balance(recipient));
        assertEquals(1, count("credit_transactions", "task_id", task.id()));
    }

    @Test void submitAndHandoffRaceCannotProduceSubmittedTaskWithWrongAssignee() throws Exception {
        grant(worker, 5);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Submit race", null, worker.id(), 0L);
        WorkTask started = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        TaskHandoffOffer offer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 3L, null, UUID.randomUUID().toString());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var submitted = executor.submit(() -> { start.await(); return collaboration.transitionTask(worker.id(), project.id(),
                    task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion()); });
            var accepted = executor.submit(() -> { start.await(); return collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()); });
            start.countDown(); int successes = 0;
            for (var future : List.of(submitted, accepted)) {
                try { future.get(15, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof ConflictException
                        || failure.getCause() instanceof ForbiddenException); }
            }
            assertEquals(1, successes);
        }
        WorkTask current = collaboration.getTask(manager.id(), project.id(), task.id()).task();
        if (current.status() == WorkTaskStatus.SUBMITTED) {
            assertEquals(worker.id(), current.assigneeUserId());
            assertEquals(5, balance(worker)); assertEquals(0, balance(recipient));
            assertEquals("CANCELLED", collaboration.listHandoffs(worker.id(), project.id()).getFirst().status());
        } else {
            assertEquals(WorkTaskStatus.IN_PROGRESS, current.status());
            assertEquals(recipient.id(), current.assigneeUserId());
            assertEquals(2, balance(worker)); assertEquals(3, balance(recipient));
        }
    }

    @Test void managerReassignmentAndHandoffAcceptanceCannotBothWin() throws Exception {
        grant(worker, 5);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Reassign race", null, worker.id(), 0L);
        TaskHandoffOffer offer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(), 3L, null, UUID.randomUUID().toString());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reassigned = executor.submit(() -> { start.await(); return collaboration.reassignTask(manager.id(), project.id(),
                    task.id(), manager.id(), task.lockVersion()); });
            var accepted = executor.submit(() -> { start.await(); return collaboration.acceptHandoff(recipient.id(), project.id(), offer.id()); });
            start.countDown(); int successes = 0;
            for (var future : List.of(reassigned, accepted)) {
                try { future.get(15, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof ConflictException); }
            }
            assertEquals(1, successes);
        }
        WorkTask current = collaboration.getTask(manager.id(), project.id(), task.id()).task();
        assertTrue(current.assigneeUserId().equals(manager.id()) || current.assigneeUserId().equals(recipient.id()));
        assertEquals(current.assigneeUserId().equals(recipient.id()) ? 2 : 5, balance(worker));
    }

    @Test void monthlyBoundaryUsesShanghaiCalendarAndCountsOnePointPerAcceptedTask() {
        var before = new DefaultCollaborationService(serviceTx, jdbcDaos, access,
                Clock.fixed(Instant.parse("2029-12-31T15:59:59Z"), ZoneOffset.UTC));
        var after = new DefaultCollaborationService(serviceTx, jdbcDaos, access,
                Clock.fixed(Instant.parse("2029-12-31T16:00:00Z"), ZoneOffset.UTC));
        for (var service : List.of(before, after)) {
            WorkTask task = service.createTask(manager.id(), project.id(), team.id(), "Boundary", null, worker.id(), 0L);
            WorkTask started = service.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
            WorkTask submitted = service.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
            service.transitionTask(manager.id(), project.id(), task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion());
        }
        assertEquals(1, collaboration.monthlyContribution(worker.id(), project.id(), "2029-12").stream()
                .filter(e -> e.userId().equals(worker.id())).findFirst().orElseThrow().score());
        assertEquals(1, collaboration.monthlyContribution(worker.id(), project.id(), "2030-01").stream()
                .filter(e -> e.userId().equals(worker.id())).findFirst().orElseThrow().score());
    }

    @Test void databaseRejectsCrossProjectTaskHandoffAndTransferReferences() {
        addMember(other, manager, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "FK scope", null, worker.id(), 0L);
        TaskHandoffOffer offer = collaboration.offerHandoff(worker.id(), project.id(), task.id(), recipient.id(),
                1L, null, UUID.randomUUID().toString());
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> new JdbcTaskCreditDao(c)
                .insertOffer(UUID.randomUUID().toString(), other.id(), task.id(), outsider.id(), manager.id(), 1L, null)));
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> new JdbcTaskCreditDao(c)
                .insertTransfer(UUID.randomUUID().toString(), other.id(), outsider.id(), manager.id(),
                        1L, "HANDOFF", offer.id(), null)));
    }
}
