package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.TestAutomationIdentityDao;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomationServiceIntegrationTest extends ServiceFixture {
    @Test void identityRegistrationAndMappingLifecycleAreExplicitAndIdempotent() {
        Scenario s = scenario("AUTOLIFE");
        TestCase first = readyCase(s.tester(), s.project(), "First", 1);
        TestCase second = readyCase(s.tester(), s.project(), "Second", 1);
        TestAutomationIdentity identity = automation.registerIdentity(s.tester().id(), s.project().id(),
                AutomationSource.JUNIT, "example.LoginTest", "wrongPassword");
        assertEquals(identity, automation.registerIdentity(s.tester().id(), s.project().id(),
                AutomationSource.JUNIT, "example.LoginTest", "wrongPassword"));
        assertEquals(identity, automation.getIdentity(s.developer().id(), identity.id()));
        assertEquals(1, automation.listIdentities(s.developer().id(), s.project().id()).size());
        assertTrue(automation.getCurrentMapping(s.tester().id(), identity.id()).isEmpty());

        TestAutomationMapping mapped = automation.mapIdentity(s.tester().id(), identity.id(), first.id(), null);
        assertEquals(AutomationMappingStatus.ACTIVE, mapped.status());
        assertEquals(mapped, automation.getCurrentMapping(s.developer().id(), identity.id()).orElseThrow());
        assertThrows(ConflictException.class,
                () -> automation.mapIdentity(s.tester().id(), identity.id(), second.id(), mapped.lockVersion()));
        TestAutomationMapping inactive = automation.deactivateMapping(s.tester().id(), identity.id(), mapped.lockVersion());
        assertEquals(AutomationMappingStatus.INACTIVE, inactive.status());
        assertTrue(automation.getCurrentMapping(s.tester().id(), identity.id()).isEmpty());
        TestAutomationMapping remapped = automation.mapIdentity(s.tester().id(), identity.id(), second.id(), inactive.lockVersion());
        assertEquals(second.id(), remapped.testCaseId());
        assertEquals(AutomationMappingStatus.ACTIVE, remapped.status());
        assertEquals(1, automation.listMappings(s.tester().id(), s.project().id()).size());
        assertThrows(ConflictException.class,
                () -> automation.deactivateMapping(s.tester().id(), identity.id(), inactive.lockVersion()));
    }

    @Test void mappingRejectsCrossProjectUnauthorizedInactiveAndArchivedWritesButReadsRemain() {
        Scenario s = scenario("AUTOPERM");
        Scenario other = scenario("AUTOOTHER");
        TestCase local = readyCase(s.tester(), s.project(), "Local", 1);
        TestCase foreign = readyCase(other.tester(), other.project(), "Foreign", 1);
        TestAutomationIdentity identity = automation.registerIdentity(s.tester().id(), s.project().id(),
                AutomationSource.JUNIT, "example.PermissionTest", "one");
        assertThrows(ValidationException.class,
                () -> automation.mapIdentity(s.tester().id(), identity.id(), foreign.id(), null));
        assertThrows(ForbiddenException.class, () -> automation.mapIdentity(s.developer().id(), identity.id(), local.id(), null));

        User inactive = actor("inactive-auto", SystemRole.USER, UserStatus.ACTIVE);
        addMember(s.project(), inactive, ProjectRole.TESTER, MembershipStatus.INACTIVE);
        assertThrows(ForbiddenException.class,
                () -> automation.registerIdentity(inactive.id(), s.project().id(), AutomationSource.JUNIT, "n", "k"));
        User disabled = actor("disabled-auto", SystemRole.USER, UserStatus.DISABLED);
        addMember(s.project(), disabled, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        assertThrows(ForbiddenException.class,
                () -> automation.registerIdentity(disabled.id(), s.project().id(), AutomationSource.JUNIT, "n", "k"));

        Project archived = projects.archive(s.admin().id(), s.project().id(), s.project().lockVersion());
        assertEquals(identity, automation.getIdentity(s.tester().id(), identity.id()));
        assertThrows(ConflictException.class,
                () -> automation.mapIdentity(s.tester().id(), identity.id(), local.id(), null));
        assertEquals(ProjectStatus.ARCHIVED, archived.status());
    }

    @Test void concurrentSameIdentityRegistrationReturnsOneIdentity() {
        Scenario s = scenario("AUTOCONCURRENT");
        CountDownLatch atInsert = new CountDownLatch(2);
        AutomationService concurrent = new DefaultAutomationService(serviceTx, connection -> {
            var daos = jdbcDaos.create(connection);
            TestAutomationIdentityDao target = daos.automationIdentities();
            return ServiceDaoDelegates.automationIdentities(daos,
                    new ServiceDaoDelegates.AutomationIdentityDelegate(target) {
                        @Override public TestAutomationIdentity insert(TestAutomationIdentity value) {
                            atInsert.countDown();
                            try {
                                if (!atInsert.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Both registrations must reach the unique-key insert");
                                }
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("Interrupted while coordinating registration", failure);
                            }
                            return super.insert(value);
                        }
                    });
        }, access);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Callable<TestAutomationIdentity> register = () -> concurrent.registerIdentity(s.tester().id(),
                        s.project().id(), AutomationSource.JUNIT, "example.ConcurrentTest", "same");
                Future<TestAutomationIdentity> first = executor.submit(register);
                Future<TestAutomationIdentity> second = executor.submit(register);
                assertEquals(first.get(), second.get());
            }
        });
        assertEquals(1, automation.listIdentities(s.tester().id(), s.project().id()).size());
    }

    private Scenario scenario(String key) {
        String suffix = key.toLowerCase();
        User admin = actor("admin-" + suffix, SystemRole.ADMIN, UserStatus.ACTIVE);
        User tester = actor("tester-" + suffix, SystemRole.USER, UserStatus.ACTIVE);
        User developer = actor("developer-" + suffix, SystemRole.USER, UserStatus.ACTIVE);
        Project project = createProject(admin, key, tester, ProjectRole.TESTER);
        addMember(project, developer, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        return new Scenario(admin, tester, developer, project);
    }

    private record Scenario(User admin, User tester, User developer, Project project) { }
}
