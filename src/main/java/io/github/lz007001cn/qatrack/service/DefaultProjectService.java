package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.exception.OptimisticLockException;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.exception.*;
import io.github.lz007001cn.qatrack.service.support.*;
import java.util.Objects;

public final class DefaultProjectService implements ProjectService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daoFactory;
    private final ProjectAccessPolicy access;

    public DefaultProjectService(ServiceTransaction transactions, ServiceDaoFactory daoFactory,
                                 ProjectAccessPolicy access) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daoFactory = Objects.requireNonNull(daoFactory);
        this.access = Objects.requireNonNull(access);
    }

    @Override public Project create(Long actorUserId, CreateProjectCommand command) {
        ServiceValidation.required(command, "command");
        String key = ServiceValidation.requiredText(command.projectKey(), 16, "projectKey");
        if (!key.matches("[A-Z][A-Z0-9]{0,15}")) {
            throw new ValidationException("projectKey must start with A-Z and contain only A-Z or 0-9");
        }
        String name = ServiceValidation.requiredText(command.name(), 160, "name");
        if ((command.initialMemberUserId() == null) != (command.initialMemberRole() == null)) {
            throw new ValidationException("initial member ID and role must be supplied together");
        }
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            access.requireAdminForWrite(daos.users(), actorUserId);
            if (daos.projects().findByKey(key).isPresent()) throw new ConflictException("projectKey already exists");
            Project project;
            try {
                project = daos.projects().insert(new Project(null, key, name, command.description(),
                        ProjectStatus.ACTIVE, actorUserId, null, null, null));
            } catch (DataAccessException failure) {
                if (failure.getVendorCode() == 1062) throw new ConflictException("projectKey already exists", failure);
                throw failure;
            }
            for (CounterEntityType type : CounterEntityType.values()) {
                daos.counters().insert(new ProjectCounter(project.id(), type, 1L));
            }
            if (command.initialMemberUserId() != null) {
                User member = daos.users().findById(command.initialMemberUserId())
                        .orElseThrow(() -> new NotFoundException("Initial member does not exist"));
                if (member.status() != UserStatus.ACTIVE) throw new ValidationException("Initial member must be active");
                daos.members().add(new ProjectMember(project.id(), member.id(), command.initialMemberRole(),
                        MembershipStatus.ACTIVE, null, null, null));
            }
            return project;
        });
    }

    @Override public java.util.List<Project> list(Long actorUserId) {
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            User actor = access.requireActiveUser(daos.users(), actorUserId);
            if (actor.systemRole() == SystemRole.ADMIN) return daos.projects().listAll();
            var rows = new java.util.ArrayList<Project>();
            for (ProjectMember member : daos.members().listByUser(actor.id(), MembershipStatus.ACTIVE)) {
                daos.projects().findById(member.projectId()).ifPresent(rows::add);
            }
            rows.sort(java.util.Comparator.comparing(Project::id));
            return java.util.List.copyOf(rows);
        });
    }

    @Override public Project get(Long actorUserId, Long projectId) {
        ServiceValidation.required(projectId, "projectId");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Project project = daos.projects().findById(projectId)
                    .orElseThrow(() -> new NotFoundException("Project does not exist"));
            access.requireProjectRead(daos.users(), daos.members(), actorUserId, project.id());
            return project;
        });
    }

    @Override public Project update(Long actorUserId, UpdateProjectCommand command) {
        ServiceValidation.required(command, "command");
        ServiceValidation.required(command.projectId(), "projectId");
        ServiceValidation.required(command.lockVersion(), "lockVersion");
        String name = ServiceValidation.requiredText(command.name(), 160, "name");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Project current = daos.projects().findByIdForUpdate(command.projectId())
                    .orElseThrow(() -> new NotFoundException("Project does not exist"));
            access.requireAdminForWrite(daos.users(), actorUserId);
            if (current.status() == ProjectStatus.ARCHIVED) throw new ConflictException("Archived project is read-only");
            try {
                return daos.projects().update(new Project(current.id(), current.projectKey(), name,
                        command.description(), current.status(), current.createdBy(), current.createdAt(),
                        current.updatedAt(), command.lockVersion()));
            } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Project", failure); }
        });
    }

    @Override public Project archive(Long actorUserId, Long projectId, Integer lockVersion) {
        ServiceValidation.required(projectId, "projectId");
        ServiceValidation.required(lockVersion, "lockVersion");
        return transactions.execute(connection -> {
            ServiceDaos daos = daoFactory.create(connection);
            Project current = daos.projects().findByIdForUpdate(projectId)
                    .orElseThrow(() -> new NotFoundException("Project does not exist"));
            access.requireAdminForWrite(daos.users(), actorUserId);
            if (current.status() == ProjectStatus.ARCHIVED) throw new ConflictException("Project is already archived");
            boolean openRun = daos.testRuns().listByProject(projectId).stream()
                    .anyMatch(run -> run.status() == TestRunStatus.IN_PROGRESS);
            if (openRun) throw new ConflictException("Project with an in-progress run cannot be archived");
            try {
                return daos.projects().update(new Project(current.id(), current.projectKey(), current.name(),
                        current.description(), ProjectStatus.ARCHIVED, current.createdBy(), current.createdAt(),
                        current.updatedAt(), lockVersion));
            } catch (OptimisticLockException failure) { throw ServiceFailures.stale("Project", failure); }
        });
    }
}
