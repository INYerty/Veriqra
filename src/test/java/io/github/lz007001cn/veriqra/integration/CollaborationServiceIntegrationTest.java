package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.JdbcCollaborationDao;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcProjectMemberDao;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CollaborationServiceIntegrationTest extends ServiceFixture {
    @Test void scopedManagerAndLeadCanRunAnAuditedTaskWithoutPlatformAdminRights() {
        User admin = actor("collab-admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        User manager = actor("collab-manager", SystemRole.USER, UserStatus.ACTIVE);
        User lead = actor("collab-lead", SystemRole.USER, UserStatus.ACTIVE);
        User worker = actor("collab-worker", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "COLLAB", manager, ProjectRole.TESTER);
        addMember(project, lead, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(project, worker, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);

        assertThrows(ForbiddenException.class, () -> collaboration.createTeam(manager.id(), project.id(), "Before", lead.id()));
        assertThrows(ForbiddenException.class, () -> collaboration.appointManager(manager.id(), project.id(), manager.id()));
        assertEquals(MembershipStatus.ACTIVE, collaboration.appointManager(admin.id(), project.id(), manager.id()).status());
        ProjectTeam team = collaboration.createTeam(manager.id(), project.id(), "Login team", lead.id());
        assertEquals(lead.id(), team.leadUserId());
        collaboration.setTeamMember(lead.id(), project.id(), team.id(), worker.id(), MembershipStatus.ACTIVE);
        WorkTask task = collaboration.createTask(lead.id(), project.id(), team.id(), "Check login", "Manual check", worker.id());
        assertEquals(WorkTaskStatus.OPEN, task.status());
        assertEquals(1, collaboration.getTask(worker.id(), project.id(), task.id()).events().size());

        WorkTask started = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        WorkTask submitted = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, "Ready", started.lockVersion());
        assertThrows(ForbiddenException.class, () -> collaboration.transitionTask(worker.id(), project.id(), task.id(),
                WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()));
        WorkTask accepted = collaboration.transitionTask(lead.id(), project.id(), task.id(), WorkTaskStatus.ACCEPTED,
                "Reviewed", submitted.lockVersion());
        assertEquals(WorkTaskStatus.ACCEPTED, accepted.status());
        assertEquals(lead.id(), accepted.acceptedBy());
        assertNotNull(accepted.acceptedAt());
        assertEquals(4, collaboration.getTask(manager.id(), project.id(), task.id()).events().size());
        assertThrows(ConflictException.class, () -> collaboration.transitionTask(lead.id(), project.id(), task.id(),
                WorkTaskStatus.CANCELLED, null, submitted.lockVersion()));
    }

    @Test void managerCanInviteExistingAccountButCannotGainCrossProjectOrAccountAdminRights() {
        User admin = actor("invite-admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        User manager = actor("invite-manager", SystemRole.USER, UserStatus.ACTIVE);
        User outsider = actor("invite-target", SystemRole.USER, UserStatus.ACTIVE);
        Project first = createProject(admin, "FIRST", manager, ProjectRole.TESTER);
        Project second = createProject(admin, "SECOND", null, null);
        collaboration.appointManager(admin.id(), first.id(), manager.id());

        assertEquals(outsider.id(), collaboration.addProjectMember(manager.id(), first.id(), outsider.username(), ProjectRole.DEVELOPER).userId());
        assertThrows(ForbiddenException.class, () -> collaboration.createTeam(manager.id(), second.id(), "Foreign", outsider.id()));
        assertThrows(ForbiddenException.class, () -> collaboration.addProjectMember(manager.id(), second.id(), outsider.username(), ProjectRole.TESTER));
        assertThrows(ForbiddenException.class, () -> collaboration.appointManager(manager.id(), first.id(), outsider.id()));
        assertThrows(ForbiddenException.class, () -> projects.create(manager.id(),
                new io.github.lz007001cn.veriqra.service.command.CreateProjectCommand("DENIED", "Denied", null, null, null)));
        collaboration.revokeManager(admin.id(), first.id(), manager.id());
        assertThrows(ForbiddenException.class, () -> collaboration.createTeam(manager.id(), first.id(), "After", outsider.id()));
    }

    @Test void teamAndTaskReferencesRejectCrossProjectRowsAndRemovalProtectsOpenWork() {
        User admin = actor("fk-admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        User manager = actor("fk-manager", SystemRole.USER, UserStatus.ACTIVE);
        User lead = actor("fk-lead", SystemRole.USER, UserStatus.ACTIVE);
        User worker = actor("fk-worker", SystemRole.USER, UserStatus.ACTIVE);
        Project first = createProject(admin, "FKFIRST", manager, ProjectRole.TESTER);
        Project second = createProject(admin, "FKSECOND", worker, ProjectRole.TESTER);
        addMember(first, lead, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(first, worker, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        collaboration.appointManager(admin.id(), first.id(), manager.id());
        ProjectTeam team = collaboration.createTeam(manager.id(), first.id(), "A", lead.id());
        collaboration.setTeamMember(manager.id(), first.id(), team.id(), worker.id(), MembershipStatus.ACTIVE);
        WorkTask task = collaboration.createTask(manager.id(), first.id(), team.id(), "Work", null, worker.id());
        assertThrows(ConflictException.class, () -> collaboration.setTeamMember(manager.id(), first.id(), team.id(),
                worker.id(), MembershipStatus.INACTIVE));
        assertThrows(NotFoundException.class, () -> collaboration.getTask(worker.id(), second.id(), task.id()));
        assertThrows(DataAccessException.class, () -> tx.inTransaction(c -> {
            new JdbcCollaborationDao(c).setTeamMember(team.id(), second.id(), worker.id(), MembershipStatus.ACTIVE);
            return null;
        }));
        WorkTask cancelled = collaboration.transitionTask(lead.id(), first.id(), task.id(), WorkTaskStatus.CANCELLED,
                "No longer needed", task.lockVersion());
        assertEquals(WorkTaskStatus.CANCELLED, cancelled.status());
        assertEquals(MembershipStatus.INACTIVE, collaboration.setTeamMember(manager.id(), first.id(), team.id(),
                worker.id(), MembershipStatus.INACTIVE).status());
        assertThrows(ForbiddenException.class, () -> collaboration.createTask(lead.id(), first.id(), team.id(), "New", null, worker.id()));
    }

    @Test void workerCannotSelfApproveAndReturnedWorkNeedsReason() {
        User admin = actor("review-admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        User manager = actor("review-manager", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "REVIEW", manager, ProjectRole.TESTER);
        collaboration.appointManager(admin.id(), project.id(), manager.id());
        ProjectTeam team = collaboration.createTeam(manager.id(), project.id(), "Solo", manager.id());
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Check", null, manager.id());
        WorkTask started = collaboration.transitionTask(manager.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        WorkTask submitted = collaboration.transitionTask(manager.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
        assertThrows(ForbiddenException.class, () -> collaboration.transitionTask(manager.id(), project.id(), task.id(),
                WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()));
        assertThrows(ForbiddenException.class, () -> collaboration.transitionTask(manager.id(), project.id(), task.id(),
                WorkTaskStatus.IN_PROGRESS, "Try again", submitted.lockVersion()));
    }

    @Test void leaderIsScopedToOwnTeamAndDisabledOrInactivePeopleLoseAccess() {
        User admin = actor("scope-admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        User manager = actor("scope-manager", SystemRole.USER, UserStatus.ACTIVE);
        User leader = actor("scope-leader", SystemRole.USER, UserStatus.ACTIVE);
        User worker = actor("scope-worker", SystemRole.USER, UserStatus.ACTIVE);
        User otherLeader = actor("scope-other-leader", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "SCOPEA", manager, ProjectRole.TESTER);
        Project otherProject = createProject(admin, "SCOPEB", otherLeader, ProjectRole.TESTER);
        addMember(project, leader, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(project, worker, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        addMember(project, otherLeader, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        collaboration.appointManager(admin.id(), project.id(), manager.id());
        ProjectTeam first = collaboration.createTeam(manager.id(), project.id(), "First", leader.id());
        ProjectTeam second = collaboration.createTeam(manager.id(), project.id(), "Second", otherLeader.id());
        collaboration.setTeamMember(leader.id(), project.id(), first.id(), worker.id(), MembershipStatus.ACTIVE);
        WorkTask task = collaboration.createTask(leader.id(), project.id(), first.id(), "Scoped task", null, worker.id());

        assertThrows(ForbiddenException.class, () -> collaboration.createTask(leader.id(), project.id(), second.id(), "Other team", null, otherLeader.id()));
        assertThrows(ForbiddenException.class, () -> collaboration.setTeamMember(leader.id(), project.id(), second.id(), worker.id(), MembershipStatus.ACTIVE));
        assertThrows(ForbiddenException.class, () -> collaboration.getTask(otherLeader.id(), project.id(), task.id()));
        assertThrows(ForbiddenException.class, () -> collaboration.listTeams(manager.id(), otherProject.id()));
        assertThrows(ForbiddenException.class, () -> collaboration.listTasks(manager.id(), otherProject.id()));
        assertThrows(NotFoundException.class, () -> collaboration.createTask(manager.id(), otherProject.id(), first.id(), "Cross project", null, worker.id()));

        tx.inTransaction(c -> {
            var dao = new JdbcUserDao(c);
            User old = dao.findById(leader.id()).orElseThrow();
            dao.update(new User(old.id(), old.username(), old.displayName(), old.passwordHash(), old.systemRole(),
                    UserStatus.DISABLED, old.createdAt(), old.updatedAt(), old.lockVersion()));
            return null;
        });
        assertThrows(ForbiddenException.class, () -> collaboration.createTask(leader.id(), project.id(), first.id(), "Disabled", null, worker.id()));
        assertEquals(1, collaboration.getTask(manager.id(), project.id(), task.id()).events().size());

        tx.inTransaction(c -> {
            var dao = new JdbcProjectMemberDao(c);
            ProjectMember old = dao.find(project.id(), manager.id()).orElseThrow();
            dao.update(new ProjectMember(old.projectId(), old.userId(), old.projectRole(), MembershipStatus.INACTIVE,
                    old.joinedAt(), old.updatedAt(), old.lockVersion()));
            return null;
        });
        assertThrows(ForbiddenException.class, () -> collaboration.createTeam(manager.id(), project.id(), "Inactive", worker.id()));
        assertEquals(1, collaboration.getTask(worker.id(), project.id(), task.id()).events().size());
    }

    @Test void competingTerminalUpdatesUseOneVersionAndKeepOneEvent() throws Exception {
        User admin = actor("race-admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        User manager = actor("race-manager", SystemRole.USER, UserStatus.ACTIVE);
        User leader = actor("race-leader", SystemRole.USER, UserStatus.ACTIVE);
        User worker = actor("race-worker", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "RACE", manager, ProjectRole.TESTER);
        addMember(project, leader, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(project, worker, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        collaboration.appointManager(admin.id(), project.id(), manager.id());
        ProjectTeam team = collaboration.createTeam(manager.id(), project.id(), "Review", leader.id());
        collaboration.setTeamMember(manager.id(), project.id(), team.id(), worker.id(), MembershipStatus.ACTIVE);
        WorkTask task = collaboration.createTask(manager.id(), project.id(), team.id(), "Race", null, worker.id());
        WorkTask started = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, null, task.lockVersion());
        WorkTask submitted = collaboration.transitionTask(worker.id(), project.id(), task.id(), WorkTaskStatus.SUBMITTED, null, started.lockVersion());
        var barrier = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> accept = executor.submit(() -> {
                try { barrier.await(); collaboration.transitionTask(manager.id(), project.id(), task.id(), WorkTaskStatus.ACCEPTED, null, submitted.lockVersion()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            });
            Future<?> returned = executor.submit(() -> {
                try { barrier.await(); collaboration.transitionTask(leader.id(), project.id(), task.id(), WorkTaskStatus.IN_PROGRESS, "Please revise", submitted.lockVersion()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            });
            barrier.countDown();
            int successes = 0, failures = 0;
            for (Future<?> future : new Future<?>[]{accept, returned}) {
                try { future.get(15, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof ConflictException, String.valueOf(e.getCause()));
                    failures++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, failures);
        }
        var detail = collaboration.getTask(worker.id(), project.id(), task.id());
        assertTrue(detail.task().status() == WorkTaskStatus.ACCEPTED || detail.task().status() == WorkTaskStatus.IN_PROGRESS);
        assertEquals(4, detail.events().size());
        assertEquals(detail.task().status(), detail.events().get(3).toStatus());
    }
}
