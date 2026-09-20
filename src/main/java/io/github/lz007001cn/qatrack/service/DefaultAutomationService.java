package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.exception.OptimisticLockException;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.exception.*;
import io.github.lz007001cn.qatrack.service.support.*;
import java.util.*;

public final class DefaultAutomationService implements AutomationService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daoFactory;
    private final ProjectAccessPolicy access;

    public DefaultAutomationService(ServiceTransaction transactions, ServiceDaoFactory daoFactory,
                                    ProjectAccessPolicy access) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daoFactory = Objects.requireNonNull(daoFactory);
        this.access = Objects.requireNonNull(access);
    }

    @Override public TestAutomationIdentity registerIdentity(Long actorUserId, Long projectId,
                                                              AutomationSource source, String namespace,
                                                              String externalKey) {
        ServiceValidation.required(projectId, "projectId");
        ServiceValidation.required(source, "source");
        String validNamespace = ServiceValidation.requiredText(namespace, 128, "namespace");
        String validKey = ServiceValidation.requiredText(externalKey, 512, "externalKey");
        try {
            return transactions.execute(connection -> {
                ServiceDaos daos = daoFactory.create(connection);
                writableProject(daos, actorUserId, projectId);
                Optional<TestAutomationIdentity> existing = daos.automationIdentities()
                        .findByExternalKey(projectId, source, validNamespace, validKey);
                return existing.orElseGet(() -> daos.automationIdentities().insert(new TestAutomationIdentity(
                        null, projectId, source, validNamespace, validKey, null)));
            });
        } catch (DataAccessException failure) {
            if (failure.getVendorCode() != 1062) throw failure;
            return recoverConcurrentRegistration(actorUserId, projectId, source, validNamespace, validKey, failure);
        }
    }

    private TestAutomationIdentity recoverConcurrentRegistration(Long actorUserId, Long projectId,
                                                                  AutomationSource source, String namespace,
                                                                  String externalKey,
                                                                  DataAccessException originalFailure) {
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            writableProject(daos, actorUserId, projectId);
            return daos.automationIdentities().findByExternalKey(projectId, source, namespace, externalKey)
                    .orElseThrow(() -> originalFailure);
        });
    }

    @Override public TestAutomationIdentity getIdentity(Long actorUserId, Long identityId) {
        ServiceValidation.required(identityId, "identityId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestAutomationIdentity identity = findIdentity(daos, identityId);
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, identity.projectId());
            return identity;
        });
    }

    @Override public List<TestAutomationIdentity> listIdentities(Long actorUserId, Long projectId) {
        ServiceValidation.required(projectId, "projectId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            requireProject(daos, projectId);
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, projectId);
            return daos.automationIdentities().listByProject(projectId);
        });
    }

    @Override public Optional<TestAutomationMapping> getCurrentMapping(Long actorUserId, Long identityId) {
        ServiceValidation.required(identityId, "identityId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestAutomationIdentity identity = findIdentity(daos, identityId);
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, identity.projectId());
            return daos.automationMappings().findByIdentity(identity.id())
                    .filter(mapping -> mapping.status() == AutomationMappingStatus.ACTIVE);
        });
    }

    @Override public List<TestAutomationMapping> listMappings(Long actorUserId, Long projectId) {
        ServiceValidation.required(projectId, "projectId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            requireProject(daos, projectId);
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, projectId);
            return daos.automationIdentities().listByProject(projectId).stream()
                    .map(identity -> daos.automationMappings().findByIdentity(identity.id()))
                    .flatMap(Optional::stream).toList();
        });
    }

    @Override public TestAutomationMapping mapIdentity(Long actorUserId, Long identityId, Long testCaseId,
                                                       Integer expectedLockVersion) {
        ServiceValidation.required(identityId, "identityId");
        ServiceValidation.required(testCaseId, "testCaseId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestAutomationIdentity preliminaryIdentity = findIdentity(daos, identityId);
            TestCase preliminaryCase = findCase(daos, testCaseId);
            ensureSameProject(preliminaryIdentity.projectId(), preliminaryCase.projectId());
            writableProject(daos, actorUserId, preliminaryIdentity.projectId());
            TestCase testCase = daos.testCases().findByIdForUpdate(testCaseId)
                    .orElseThrow(() -> new NotFoundException("Test case does not exist"));
            ensureSameProject(preliminaryIdentity.projectId(), testCase.projectId());
            TestAutomationIdentity identity = daos.automationIdentities().findByIdForUpdate(identityId)
                    .orElseThrow(() -> new NotFoundException("Automation identity does not exist"));
            ensureSameProject(identity.projectId(), testCase.projectId());
            Optional<TestAutomationMapping> current = daos.automationMappings().findByIdentityForUpdate(identity.id());
            if (current.isEmpty()) {
                if (expectedLockVersion != null) throw new ConflictException("Automation mapping does not yet exist");
                return daos.automationMappings().add(new TestAutomationMapping(null, identity.id(), testCase.id(),
                        AutomationMappingStatus.ACTIVE, actorUserId, null, null, null));
            }
            TestAutomationMapping mapping = current.get();
            requireVersion(mapping, expectedLockVersion);
            if (mapping.status() == AutomationMappingStatus.ACTIVE) {
                if (Objects.equals(mapping.testCaseId(), testCase.id())) return mapping;
                throw new ConflictException("Active mapping must be deactivated before remapping");
            }
            if (!Objects.equals(mapping.testCaseId(), testCase.id())
                    && daos.attempts().hasAutomationMappingReferenceForUpdate(mapping.id())) {
                throw new ConflictException("A mapping referenced by execution history cannot be rebound");
            }
            return updateMapping(daos, new TestAutomationMapping(mapping.id(), mapping.automationIdentityId(),
                    testCase.id(), AutomationMappingStatus.ACTIVE, mapping.createdBy(), mapping.createdAt(),
                    mapping.updatedAt(), mapping.lockVersion()));
        });
    }

    @Override public TestAutomationMapping deactivateMapping(Long actorUserId, Long identityId,
                                                              Integer expectedLockVersion) {
        ServiceValidation.required(identityId, "identityId");
        ServiceValidation.required(expectedLockVersion, "expectedLockVersion");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            TestAutomationIdentity preliminary = findIdentity(daos, identityId);
            writableProject(daos, actorUserId, preliminary.projectId());
            TestAutomationIdentity identity = daos.automationIdentities().findByIdForUpdate(identityId)
                    .orElseThrow(() -> new NotFoundException("Automation identity does not exist"));
            TestAutomationMapping mapping = daos.automationMappings().findByIdentityForUpdate(identity.id())
                    .orElseThrow(() -> new NotFoundException("Automation mapping does not exist"));
            requireVersion(mapping, expectedLockVersion);
            if (mapping.status() == AutomationMappingStatus.INACTIVE) return mapping;
            return updateMapping(daos, new TestAutomationMapping(mapping.id(), mapping.automationIdentityId(),
                    mapping.testCaseId(), AutomationMappingStatus.INACTIVE, mapping.createdBy(), mapping.createdAt(),
                    mapping.updatedAt(), mapping.lockVersion()));
        });
    }

    private Project writableProject(ServiceDaos daos, Long actorUserId, Long projectId) {
        Project project = daos.projects().findByIdForShare(projectId)
                .orElseThrow(() -> new NotFoundException("Project does not exist"));
        access.requireAssetWrite(daos.users(), daos.members(), actorUserId, project.id());
        if (project.status() != ProjectStatus.ACTIVE) throw new ConflictException("Archived project is read-only");
        return project;
    }

    private static Project requireProject(ServiceDaos daos, Long projectId) {
        return daos.projects().findById(projectId).orElseThrow(() -> new NotFoundException("Project does not exist"));
    }
    private static TestAutomationIdentity findIdentity(ServiceDaos daos, Long id) {
        return daos.automationIdentities().findById(id)
                .orElseThrow(() -> new NotFoundException("Automation identity does not exist"));
    }
    private static TestCase findCase(ServiceDaos daos, Long id) {
        return daos.testCases().findById(id).orElseThrow(() -> new NotFoundException("Test case does not exist"));
    }
    private static void ensureSameProject(Long identityProject, Long caseProject) {
        if (!Objects.equals(identityProject, caseProject)) {
            throw new ValidationException("Automation identity and test case belong to different projects");
        }
    }
    private static void requireVersion(TestAutomationMapping mapping, Integer expected) {
        ServiceValidation.required(expected, "expectedLockVersion");
        if (!Objects.equals(mapping.lockVersion(), expected)) throw new ConflictException("Automation mapping was changed");
    }
    private static TestAutomationMapping updateMapping(ServiceDaos daos, TestAutomationMapping value) {
        try { return daos.automationMappings().update(value); }
        catch (OptimisticLockException failure) { throw ServiceFailures.stale("Automation mapping", failure); }
    }
}
