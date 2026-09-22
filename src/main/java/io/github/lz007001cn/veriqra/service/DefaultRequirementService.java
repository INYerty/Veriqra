package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.exception.OptimisticLockException;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import io.github.lz007001cn.veriqra.service.support.*;
import java.util.*;

public final class DefaultRequirementService implements RequirementService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daoFactory;
    private final ProjectAccessPolicy access;

    public DefaultRequirementService(ServiceTransaction transactions, ServiceDaoFactory daoFactory,
                                     ProjectAccessPolicy access) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daoFactory = Objects.requireNonNull(daoFactory);
        this.access = Objects.requireNonNull(access);
    }

    @Override public Requirement create(Long actorUserId, CreateRequirementCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.projectId(), "projectId");
        String title = ServiceValidation.requiredText(command.title(), 240, "title");
        Priority priority = ServiceValidation.required(command.priority(), "priority");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Project project = writableProject(daos, actorUserId, command.projectId());
            long key = daos.counters().allocateNext(project.id(), CounterEntityType.REQ);
            return daos.requirements().insert(new Requirement(null, project.id(), key, title,
                    command.description(), priority, RequirementStatus.DRAFT, actorUserId, null, null, null));
        });
    }

    @Override public Requirement get(Long actorUserId, Long requirementId) {
        return get(actorUserId, null, requirementId);
    }

    @Override public Requirement get(Long actorUserId, Long projectId, Long requirementId) {
        ServiceValidation.required(requirementId, "requirementId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Requirement value = daos.requirements().findById(requirementId)
                    .orElseThrow(() -> new NotFoundException("Requirement does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, value.projectId());
            ProjectOwnership.require(value.projectId(), projectId);
            return value;
        });
    }

    @Override public List<Requirement> listByProject(Long actorUserId, Long projectId) {
        ServiceValidation.required(projectId, "projectId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            daos.projects().findById(projectId).orElseThrow(() -> new NotFoundException("Project does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, projectId);
            return daos.requirements().listByProject(projectId);
        });
    }

    @Override public Requirement update(Long actorUserId, UpdateRequirementCommand command) {
        return update(actorUserId, null, command);
    }

    @Override public Requirement update(Long actorUserId, Long projectId, UpdateRequirementCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.requirementId(), "requirementId");
        ServiceValidation.required(command.lockVersion(), "lockVersion");
        String title = ServiceValidation.requiredText(command.title(), 240, "title");
        Priority priority = ServiceValidation.required(command.priority(), "priority");
        RequirementStatus status = ServiceValidation.required(command.status(), "status");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Requirement preliminary = daos.requirements().findById(command.requirementId())
                    .orElseThrow(() -> new NotFoundException("Requirement does not exist"));
            writableProject(daos, actorUserId, preliminary.projectId());
            ProjectOwnership.require(preliminary.projectId(), projectId);
            Requirement current = daos.requirements().findByIdForUpdate(command.requirementId())
                    .orElseThrow(() -> new NotFoundException("Requirement does not exist"));
            if (!Objects.equals(current.projectId(), preliminary.projectId())) {
                throw new ConflictException("Requirement project changed during update");
            }
            if (current.status() == RequirementStatus.ARCHIVED) {
                throw new ConflictException("Archived requirement is read-only");
            }
            boolean materialChange = !Objects.equals(current.title(), title)
                    || !Objects.equals(current.description(), command.description());
            Requirement updated;
            try {
                updated = daos.requirements().update(new Requirement(current.id(), current.projectId(), current.keyNo(),
                        title, command.description(), priority, status, current.createdBy(), current.createdAt(),
                        current.updatedAt(), command.lockVersion()));
            } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Requirement", failure); }
            if (materialChange) daos.traceability().markConfirmedNeedsReviewByRequirement(current.id());
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
}
