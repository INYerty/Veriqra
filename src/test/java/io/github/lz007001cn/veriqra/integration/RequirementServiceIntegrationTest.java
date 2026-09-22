package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequirementServiceIntegrationTest extends ServiceFixture {
    @Test void createAllocatesReqNumbersAndRequiresTesterOrAdmin() {
        User admin = actor("admin-req", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-req", SystemRole.USER, UserStatus.ACTIVE);
        User developer = actor("developer-req", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "REQS", tester, ProjectRole.TESTER);
        addMember(project, developer, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        Requirement first = createRequirement(tester, project, "First");
        Requirement second = createRequirement(admin, project, "Second");
        assertEquals(1L, first.keyNo()); assertEquals(2L, second.keyNo());
        assertThrows(ForbiddenException.class, () -> createRequirement(developer, project, "Forbidden"));
    }

    @Test void failedRequirementInsertRollsBackCounterAllocation() {
        User admin = actor("admin-req-rollback", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-req-rollback", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "REQROLL", tester, ProjectRole.TESTER);
        var failingFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var failing = new ServiceDaoDelegates.RequirementDelegate(d.requirements()) {
                @Override public Requirement insert(Requirement value) {
                    target.insert(value);
                    throw new DataAccessException("Injected failure after Requirement insert");
                }
            };
            return ServiceDaoDelegates.requirement(d, failing);
        };
        var service = new DefaultRequirementService(serviceTx, failingFactory, access);
        assertThrows(DataAccessException.class, () -> service.create(tester.id(),
                new CreateRequirementCommand(project.id(), "Will roll back", null, Priority.MEDIUM)));
        tx.inTransaction(c -> {
            assertTrue(new io.github.lz007001cn.veriqra.dao.jdbc.JdbcRequirementDao(c).listByProject(project.id()).isEmpty());
            assertEquals(1L, new io.github.lz007001cn.veriqra.dao.jdbc.JdbcProjectCounterDao(c)
                    .find(project.id(), CounterEntityType.REQ).orElseThrow().nextValue()); return null;
        });
    }

    @Test void materialUpdateInvalidatesConfirmedLinksButNeverReactivatesRemovedRows() {
        User admin = actor("admin-req-links", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-req-links", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "REQLINK", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, project, "Before");
        TestCase confirmedCase = createCase(tester, project, "Confirmed case", 1);
        TestCase removedCase = createCase(tester, project, "Removed case", 1);
        traceability.attach(tester.id(), requirement.id(), confirmedCase.id());
        traceability.confirm(tester.id(), requirement.id(), confirmedCase.id());
        traceability.attach(tester.id(), requirement.id(), removedCase.id());
        traceability.remove(tester.id(), requirement.id(), removedCase.id());
        Requirement nonMaterial = requirements.update(tester.id(), new UpdateRequirementCommand(requirement.id(),
                requirement.title(), requirement.description(), Priority.HIGH, RequirementStatus.ACTIVE,
                requirement.lockVersion()));
        assertTrue(traceability.isConfirmedLink(tester.id(), requirement.id(), confirmedCase.id()));
        Requirement updated = requirements.update(tester.id(), new UpdateRequirementCommand(requirement.id(),
                "After", requirement.description(), nonMaterial.priority(), nonMaterial.status(), nonMaterial.lockVersion()));
        assertEquals("After", updated.title());
        tx.inTransaction(c -> {
            var dao = new io.github.lz007001cn.veriqra.dao.jdbc.JdbcTestCaseRequirementDao(c);
            assertEquals(TraceabilityStatus.NEEDS_REVIEW, dao.find(requirement.id(), confirmedCase.id()).orElseThrow().status());
            assertEquals(TraceabilityStatus.REMOVED, dao.find(requirement.id(), removedCase.id()).orElseThrow().status());
            return null;
        });
        assertThrows(ConflictException.class, () -> requirements.update(tester.id(), new UpdateRequirementCommand(
                requirement.id(), "Stale", null, Priority.HIGH, RequirementStatus.ACTIVE, requirement.lockVersion())));
    }

    @Test void traceabilityInvalidationFailureRollsBackRequirementUpdate() {
        User admin = actor("admin-req-fault", SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-req-fault", SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, "REQFAULT", tester, ProjectRole.TESTER);
        Requirement requirement = createRequirement(tester, project, "Original");
        TestCase testCase = createCase(tester, project, "Case", 1);
        traceability.attach(tester.id(), requirement.id(), testCase.id());
        traceability.confirm(tester.id(), requirement.id(), testCase.id());
        var failingFactory = (io.github.lz007001cn.veriqra.service.support.ServiceDaoFactory) connection -> {
            var d = jdbcDaos.create(connection);
            var failing = new ServiceDaoDelegates.TraceabilityDelegate(d.traceability()) {
                @Override public int markConfirmedNeedsReviewByRequirement(Long id) {
                    target.markConfirmedNeedsReviewByRequirement(id);
                    throw new DataAccessException("Injected traceability invalidation failure");
                }
            };
            return ServiceDaoDelegates.traceability(d, failing);
        };
        var service = new DefaultRequirementService(serviceTx, failingFactory, access);
        assertThrows(DataAccessException.class, () -> service.update(tester.id(), new UpdateRequirementCommand(
                requirement.id(), "Changed", requirement.description(), requirement.priority(),
                RequirementStatus.ACTIVE, requirement.lockVersion())));
        assertEquals("Original", requirements.get(tester.id(), requirement.id()).title());
        assertTrue(traceability.isConfirmedLink(tester.id(), requirement.id(), testCase.id()));
    }
}
