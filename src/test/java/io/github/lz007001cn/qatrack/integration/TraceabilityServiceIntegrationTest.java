package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.UpdateRequirementCommand;
import io.github.lz007001cn.qatrack.service.exception.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class TraceabilityServiceIntegrationTest extends ServiceFixture {
    @Test void attachConfirmRemoveAndReattachPreserveIdentityWithClearBusinessPredicates() {
        User admin = actor("admin-trace", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-trace", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "TRACE", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, project, "Requirement");
        TestCase testCase = createCase(tester, project, "Case", 1);
        var attached = traceability.attach(tester.id(), requirement.id(), testCase.id());
        assertEquals(TraceabilityStatus.NEEDS_REVIEW, attached.status());
        assertTrue(traceability.isActiveLink(tester.id(), requirement.id(), testCase.id()));
        assertFalse(traceability.isConfirmedLink(tester.id(), requirement.id(), testCase.id()));
        var confirmed = traceability.confirm(tester.id(), requirement.id(), testCase.id());
        assertEquals(TraceabilityStatus.CONFIRMED, confirmed.status());
        assertEquals(tester.id(), confirmed.reviewedBy());
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0), confirmed.reviewedAt());
        var removed = traceability.remove(tester.id(), requirement.id(), testCase.id());
        assertEquals(TraceabilityStatus.REMOVED, removed.status());
        assertFalse(traceability.isActiveLink(tester.id(), requirement.id(), testCase.id()));
        assertThrows(ConflictException.class,
                () -> traceability.confirm(tester.id(), requirement.id(), testCase.id()));
        assertEquals(removed, traceability.remove(tester.id(), requirement.id(), testCase.id()));
        var reattached = traceability.attach(tester.id(), requirement.id(), testCase.id());
        assertEquals(TraceabilityStatus.NEEDS_REVIEW, reattached.status());
        assertEquals(attached.linkedBy(), reattached.linkedBy()); assertEquals(attached.linkedAt(), reattached.linkedAt());
    }

    @Test void crossProjectAttachIsRejectedEvenWhenActorCanWriteBothProjects() {
        User admin = actor("admin-cross", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-cross", SystemRole.USER, UserStatus.ACTIVE);
        Project first = createProject(admin, "TRACEA", tester, ProjectRole.TESTER);
        Project second = createProject(admin, "TRACEB", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, first, "Requirement");
        TestCase testCase = createCase(tester, second, "Case", 1);
        assertThrows(ValidationException.class,
                () -> traceability.attach(tester.id(), requirement.id(), testCase.id()));
        tx.inTransaction(c -> {
            assertFalse(new io.github.lz007001cn.qatrack.dao.jdbc.JdbcTestCaseRequirementDao(c)
                    .existsRecord(requirement.id(), testCase.id())); return null;
        });
    }

    @Test void archivedEndpointAndInactiveMembershipBlockConfirmation() {
        User admin = actor("admin-archived-trace", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-archived-trace", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "TRARCH", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, project, "Requirement");
        TestCase testCase = createCase(tester, project, "Case", 1);
        traceability.attach(tester.id(), requirement.id(), testCase.id());
        Requirement archived = requirements.update(tester.id(), new UpdateRequirementCommand(requirement.id(),
                requirement.title(), requirement.description(), requirement.priority(), RequirementStatus.ARCHIVED,
                requirement.lockVersion()));
        assertEquals(RequirementStatus.ARCHIVED, archived.status());
        assertThrows(ConflictException.class,
                () -> traceability.confirm(tester.id(), requirement.id(), testCase.id()));

        Requirement secondRequirement = createRequirement(admin, project, "Second");
        traceability.attach(tester.id(), secondRequirement.id(), testCase.id());
        tx.inTransaction(c -> {
            var dao = new io.github.lz007001cn.qatrack.dao.jdbc.JdbcProjectMemberDao(c);
            var member = dao.find(project.id(), tester.id()).orElseThrow();
            dao.update(new ProjectMember(member.projectId(), member.userId(), member.projectRole(),
                    MembershipStatus.INACTIVE, member.joinedAt(), member.updatedAt(), member.lockVersion())); return null;
        });
        assertThrows(ForbiddenException.class,
                () -> traceability.confirm(tester.id(), secondRequirement.id(), testCase.id()));
    }
}
