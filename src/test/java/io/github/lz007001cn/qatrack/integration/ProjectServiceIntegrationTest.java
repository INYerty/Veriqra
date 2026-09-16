package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.exception.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceIntegrationTest extends ServiceFixture {
    @Test void adminCreatesProjectFourCountersAndOptionalMemberThenUpdatesAndArchives() {
        User admin = actor("admin-project", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-project", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "SVC", tester, ProjectRole.TESTER);
        tx.inTransaction(c -> {
            assertEquals(4, new JdbcProjectCounterDao(c).listByProject(project.id()).size());
            var member = new JdbcProjectMemberDao(c).find(project.id(), tester.id()).orElseThrow();
            assertEquals(ProjectRole.TESTER, member.projectRole()); assertEquals(MembershipStatus.ACTIVE, member.status());
            return null;
        });
        Project updated = projects.update(admin.id(), new UpdateProjectCommand(project.id(), "Updated", "desc", project.lockVersion()));
        assertEquals("Updated", updated.name());
        Project archived = projects.archive(admin.id(), project.id(), updated.lockVersion());
        assertEquals(ProjectStatus.ARCHIVED, archived.status());
        assertThrows(ConflictException.class, () -> projects.update(admin.id(),
                new UpdateProjectCommand(project.id(), "Again", null, archived.lockVersion())));
        assertThrows(ConflictException.class, () -> requirements.create(tester.id(),
                new CreateRequirementCommand(project.id(), "No archived write", null, Priority.MEDIUM)));
    }

    @Test void projectManagementRequiresActiveAdminWhileMembersReadAccordingToStatus() {
        User admin = actor("admin-access", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-access", SystemRole.USER, UserStatus.ACTIVE);
        User developer = actor("developer-access", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "ACCESS", tester, ProjectRole.TESTER);
        addMember(project, developer, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        assertEquals(project.id(), projects.get(developer.id(), project.id()).id());
        assertThrows(ForbiddenException.class, () -> projects.create(tester.id(),
                new CreateProjectCommand("NOPE", "Nope", null, null, null)));
        tx.inTransaction(c -> {
            var dao = new JdbcProjectMemberDao(c); var member = dao.find(project.id(), developer.id()).orElseThrow();
            dao.update(new ProjectMember(member.projectId(), member.userId(), member.projectRole(),
                    MembershipStatus.INACTIVE, member.joinedAt(), member.updatedAt(), member.lockVersion())); return null;
        });
        assertThrows(ForbiddenException.class, () -> projects.get(developer.id(), project.id()));
    }

    @Test void inProgressRunBlocksArchive() {
        User admin = actor("admin-run", SystemRole.ADMIN, UserStatus.ACTIVE);
        Project project = createProject(admin, "RUNLOCK", null, null);
        tx.inTransaction(c -> {
            new JdbcTestRunDao(c).insert(new TestRun(null, project.id(), null, "Run", null, null,
                    TestRunStatus.IN_PROGRESS, null, admin.id(), null, null, null)); return null;
        });
        assertThrows(ConflictException.class, () -> projects.archive(admin.id(), project.id(), project.lockVersion()));
    }
}
