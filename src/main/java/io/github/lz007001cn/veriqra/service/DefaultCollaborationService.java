package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import io.github.lz007001cn.veriqra.service.support.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Project-scoped collaboration. Platform account administration remains separate. */
public final class DefaultCollaborationService implements CollaborationService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory factory;
    private final ProjectAccessPolicy access;
    private final Clock clock;

    public DefaultCollaborationService(ServiceTransaction transactions, ServiceDaoFactory factory,
                                       ProjectAccessPolicy access, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions);
        this.factory = Objects.requireNonNull(factory);
        this.access = Objects.requireNonNull(access);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public List<ProjectManager> listManagers(Long actorId, Long projectId) {
        long project = id(projectId, "projectId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            readableProject(d, actorId, project);
            return d.collaboration().listManagers(project);
        });
    }
    @Override public ProjectManager appointManager(Long actorId, Long projectId, Long memberId) {
        long project = id(projectId, "projectId"), member = id(memberId, "memberId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProjectForUpdate(d, project);
            access.requireAdminForWrite(d.users(), actorId);
            activeMember(d, project, member);
            if (activeManager(d, project, member)) throw new ConflictException("User is already a project manager");
            return d.collaboration().appointManager(project, member, actorId);
        });
    }
    @Override public void revokeManager(Long actorId, Long projectId, Long memberId) {
        long project = id(projectId, "projectId"), member = id(memberId, "memberId");
        transactions.execute(c -> {
            var d = factory.create(c);
            activeProjectForUpdate(d, project);
            access.requireAdminForWrite(d.users(), actorId);
            var current = d.collaboration().findManager(project, member)
                    .orElseThrow(() -> new NotFoundException("Project manager does not exist"));
            if (current.status() != MembershipStatus.ACTIVE) throw new ConflictException("Manager is already inactive");
            d.collaboration().revokeManager(project, member);
            return null;
        });
    }
    @Override public List<MemberView> listProjectMembers(Long actorId, Long projectId) {
        long project = id(projectId, "projectId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            readableProject(d, actorId, project);
            return d.members().listByProject(project).stream().map(m -> view(d, m)).toList();
        });
    }
    @Override public MemberView addProjectMember(Long actorId, Long projectId, String username, ProjectRole role) {
        long project = id(projectId, "projectId");
        String name = ServiceValidation.requiredText(username, 64, "username").trim();
        ServiceValidation.required(role, "projectRole");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            User actor = access.requireActiveUser(d.users(), actorId);
            if (actor.systemRole() == SystemRole.ADMIN) access.requireAdminForWrite(d.users(), actorId);
            else requireManager(d, project, actorId);
            User target = d.users().findByUsername(name).orElseThrow(() -> new NotFoundException("User does not exist"));
            target = d.users().findByIdForShare(target.id()).orElseThrow(() -> new NotFoundException("User does not exist"));
            if (target.status() != UserStatus.ACTIVE) throw new ConflictException("User is disabled");
            ProjectMember current = d.members().find(project, target.id()).orElse(null);
            ProjectMember member;
            if (current == null) {
                try {
                    member = d.members().add(new ProjectMember(project, target.id(), role, MembershipStatus.ACTIVE,
                            null, null, null));
                } catch (DataAccessException failure) {
                    if (failure.getVendorCode() == 1062) throw new ConflictException("Project membership already exists", failure);
                    throw failure;
                }
            } else {
                if (current.status() == MembershipStatus.ACTIVE) throw new ConflictException("User is already a project member");
                member = d.members().update(new ProjectMember(project, target.id(), role, MembershipStatus.ACTIVE,
                        current.joinedAt(), current.updatedAt(), current.lockVersion()));
            }
            return view(d, member);
        });
    }

    @Override public List<ProjectTeam> listTeams(Long actorId, Long projectId) {
        long project = id(projectId, "projectId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            readableProject(d, actorId, project);
            return d.collaboration().listTeams(project);
        });
    }
    @Override public ProjectTeam createTeam(Long actorId, Long projectId, String name, Long leadUserId) {
        long project = id(projectId, "projectId"), lead = id(leadUserId, "leadUserId");
        String title = ServiceValidation.requiredText(name, 120, "name").trim();
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            requireManager(d, project, actorId);
            activeMember(d, project, lead);
            try {
                ProjectTeam team = d.collaboration().insertTeam(project, title, lead, actorId);
                d.collaboration().setTeamMember(team.id(), project, lead, MembershipStatus.ACTIVE);
                return team;
            } catch (DataAccessException failure) {
                if (failure.getVendorCode() == 1062) throw new ConflictException("Team name already exists in project", failure);
                throw failure;
            }
        });
    }
    @Override public ProjectTeam changeTeamLead(Long actorId, Long projectId, Long teamId, Long leadUserId,
                                                Integer expectedVersion) {
        long project = id(projectId, "projectId"), team = id(teamId, "teamId"), lead = id(leadUserId, "leadUserId");
        ServiceValidation.required(expectedVersion, "expectedVersion");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            requireManager(d, project, actorId);
            ProjectTeam current = activeTeam(d, project, team);
            requireVersion(current.lockVersion(), expectedVersion);
            activeMember(d, project, lead);
            d.collaboration().setTeamMember(team, project, lead, MembershipStatus.ACTIVE);
            return d.collaboration().updateTeam(new ProjectTeam(team, project, current.name(), lead,
                    current.status(), current.createdBy(), current.createdAt(), current.updatedAt(), expectedVersion));
        });
    }
    @Override public List<TeamMember> listTeamMembers(Long actorId, Long projectId, Long teamId) {
        long project = id(projectId, "projectId"), team = id(teamId, "teamId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            readableProject(d, actorId, project);
            requireTeam(d, project, team);
            return d.collaboration().listTeamMembers(team);
        });
    }
    @Override public TeamMember setTeamMember(Long actorId, Long projectId, Long teamId, Long memberId,
                                               MembershipStatus status) {
        long project = id(projectId, "projectId"), team = id(teamId, "teamId"), member = id(memberId, "memberId");
        ServiceValidation.required(status, "status");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            ProjectTeam current = activeTeam(d, project, team);
            requireManagerOrLead(d, current, actorId);
            if (status == MembershipStatus.ACTIVE) activeMember(d, project, member);
            else {
                if (current.leadUserId().equals(member)) throw new ConflictException("Change team lead before removing this member");
                TeamMember old = d.collaboration().findTeamMember(team, member)
                        .orElseThrow(() -> new NotFoundException("Team member does not exist"));
                if (old.status() != MembershipStatus.ACTIVE) throw new ConflictException("Team member is already inactive");
                boolean unfinished = d.collaboration().listTasks(project).stream().anyMatch(t -> t.teamId().equals(team)
                        && t.assigneeUserId().equals(member) && t.status() != WorkTaskStatus.ACCEPTED
                        && t.status() != WorkTaskStatus.CANCELLED);
                if (unfinished) throw new ConflictException("Reassign unfinished tasks before removing this member");
            }
            return d.collaboration().setTeamMember(team, project, member, status);
        });
    }

    @Override public List<WorkTask> listTasks(Long actorId, Long projectId) {
        long project = id(projectId, "projectId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            readableProject(d, actorId, project);
            boolean manager = activeManager(d, project, actorId);
            return d.collaboration().listTasks(project).stream()
                    .filter(task -> manager || task.assigneeUserId().equals(actorId)
                            || isActiveLead(d, task.teamId(), actorId)).toList();
        });
    }
    @Override public TaskDetail getTask(Long actorId, Long projectId, Long taskId) {
        long project = id(projectId, "projectId"), task = id(taskId, "taskId");
        return transactions.execute(c -> {
            var d = factory.create(c);
            readableProject(d, actorId, project);
            WorkTask value = requireTask(d, project, task);
            if (!value.assigneeUserId().equals(actorId) && !activeManager(d, project, actorId)
                    && !isActiveLead(d, value.teamId(), actorId)) throw new ForbiddenException("Task access is required");
            return new TaskDetail(value, d.collaboration().listEvents(task));
        });
    }
    @Override public WorkTask createTask(Long actorId, Long projectId, Long teamId, String title,
                                         String description, Long assigneeId) {
        long project = id(projectId, "projectId"), team = id(teamId, "teamId"), assignee = id(assigneeId, "assigneeId");
        String name = ServiceValidation.requiredText(title, 240, "title").trim();
        ServiceValidation.optionalText(description, 10000, "description");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            ProjectTeam current = activeTeam(d, project, team);
            requireManagerOrLead(d, current, actorId);
            activeTeamMember(d, current, assignee);
            WorkTask task = d.collaboration().insertTask(project, team, name, description, assignee, actorId);
            event(d, task, actorId, WorkTaskEventType.CREATED, null, null, null);
            return task;
        });
    }
    @Override public WorkTask reassignTask(Long actorId, Long projectId, Long taskId, Long assigneeId,
                                           Integer expectedVersion) {
        long project = id(projectId, "projectId"), task = id(taskId, "taskId"), assignee = id(assigneeId, "assigneeId");
        ServiceValidation.required(expectedVersion, "expectedVersion");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            WorkTask observed = requireTask(d, project, task);
            ProjectTeam team = activeTeam(d, project, observed.teamId());
            WorkTask current = lockedTask(d, project, task);
            requireVersion(current.lockVersion(), expectedVersion);
            requireManagerOrLead(d, team, actorId);
            if (current.status() != WorkTaskStatus.OPEN && current.status() != WorkTaskStatus.IN_PROGRESS)
                throw new ConflictException("Only open or in-progress tasks can be reassigned");
            if (current.assigneeUserId().equals(assignee)) throw new ConflictException("Task already has this assignee");
            activeTeamMember(d, team, assignee);
            WorkTask updated = d.collaboration().updateTask(copy(current, assignee, current.status(), null, null));
            event(d, updated, actorId, WorkTaskEventType.REASSIGNED, current.status(), current.assigneeUserId(), null);
            return updated;
        });
    }
    @Override public WorkTask transitionTask(Long actorId, Long projectId, Long taskId, WorkTaskStatus next,
                                             String note, Integer expectedVersion) {
        long project = id(projectId, "projectId"), task = id(taskId, "taskId");
        ServiceValidation.required(next, "status");
        ServiceValidation.required(expectedVersion, "expectedVersion");
        String reason = ServiceValidation.optionalText(note, 500, "note");
        return transactions.execute(c -> {
            var d = factory.create(c);
            activeProject(d, project);
            WorkTask observed = requireTask(d, project, task);
            ProjectTeam team = activeTeam(d, project, observed.teamId());
            WorkTask current = lockedTask(d, project, task);
            requireVersion(current.lockVersion(), expectedVersion);
            User actor = activeMember(d, project, actorId);
            boolean assignee = current.assigneeUserId().equals(actor.id());
            boolean reviewer = activeManager(d, project, actor.id()) || isActiveLead(d, team.id(), actor.id());
            WorkTaskEventType type;
            switch (next) {
                case IN_PROGRESS -> {
                    if (current.status() == WorkTaskStatus.OPEN && assignee) type = WorkTaskEventType.STARTED;
                    else if (current.status() == WorkTaskStatus.SUBMITTED && reviewer && !assignee) {
                        if (reason == null || reason.isBlank()) throw new ValidationException("Return note is required");
                        type = WorkTaskEventType.RETURNED;
                    } else throw new ForbiddenException("This actor cannot start or return the task");
                }
                case SUBMITTED -> {
                    if (current.status() != WorkTaskStatus.IN_PROGRESS || !assignee)
                        throw new ForbiddenException("Only the assignee can submit in-progress work");
                    type = WorkTaskEventType.SUBMITTED;
                }
                case ACCEPTED -> {
                    if (current.status() != WorkTaskStatus.SUBMITTED || !reviewer || assignee)
                        throw new ForbiddenException("Independent project or team review is required");
                    type = WorkTaskEventType.ACCEPTED;
                }
                case CANCELLED -> {
                    if (current.status() != WorkTaskStatus.OPEN && current.status() != WorkTaskStatus.IN_PROGRESS)
                        throw new ConflictException("Only open or in-progress work can be cancelled");
                    if (!reviewer) throw new ForbiddenException("Project or team lead access is required");
                    type = WorkTaskEventType.CANCELLED;
                }
                default -> throw new ConflictException("Unsupported task transition");
            }
            Long acceptedBy = next == WorkTaskStatus.ACCEPTED ? actor.id() : null;
            LocalDateTime acceptedAt = acceptedBy == null ? null : LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
            WorkTask updated = d.collaboration().updateTask(copy(current, current.assigneeUserId(), next, acceptedBy, acceptedAt));
            event(d, updated, actor.id(), type, current.status(), current.assigneeUserId(), reason);
            return updated;
        });
    }

    private static long id(Long value, String field) {
        if (value == null || value <= 0) throw new ValidationException(field + " must be a positive ID");
        return value;
    }
    private Project readableProject(ServiceDaos d, Long actorId, long projectId) {
        Project project = d.projects().findById(projectId).orElseThrow(() -> new NotFoundException("Project does not exist"));
        access.requireProjectRead(d.users(), d.members(), actorId, projectId);
        return project;
    }
    private Project activeProject(ServiceDaos d, long projectId) {
        Project project = d.projects().findByIdForShare(projectId).orElseThrow(() -> new NotFoundException("Project does not exist"));
        if (project.status() != ProjectStatus.ACTIVE) throw new ConflictException("Archived project is read-only");
        return project;
    }
    private Project activeProjectForUpdate(ServiceDaos d, long projectId) {
        Project project = d.projects().findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("Project does not exist"));
        if (project.status() != ProjectStatus.ACTIVE) throw new ConflictException("Archived project is read-only");
        return project;
    }
    private User activeMember(ServiceDaos d, long projectId, long userId) {
        User user = d.users().findByIdForShare(userId)
                .orElseThrow(() -> new ForbiddenException("Active user is required"));
        if (user.status() != UserStatus.ACTIVE) throw new ForbiddenException("Active user is required");
        ProjectMember member = d.members().findForShare(projectId, userId)
                .orElseThrow(() -> new ForbiddenException("Active project membership is required"));
        if (member.status() != MembershipStatus.ACTIVE) throw new ForbiddenException("Active project membership is required");
        return user;
    }
    private void requireManager(ServiceDaos d, long projectId, Long actorId) {
        activeMember(d, projectId, id(actorId, "actorId"));
        if (!activeManager(d, projectId, actorId)) throw new ForbiddenException("Project manager access is required");
    }
    private static boolean activeManager(ServiceDaos d, long projectId, Long actorId) {
        return d.members().find(projectId, actorId).map(m -> m.status() == MembershipStatus.ACTIVE).orElse(false)
                && d.collaboration().findManager(projectId, actorId)
                        .map(m -> m.status() == MembershipStatus.ACTIVE).orElse(false);
    }
    private ProjectTeam requireTeam(ServiceDaos d, long projectId, long teamId) {
        ProjectTeam team = d.collaboration().findTeam(teamId).orElseThrow(() -> new NotFoundException("Team does not exist"));
        if (!team.projectId().equals(projectId)) throw new NotFoundException("Team does not exist in project");
        return team;
    }
    private ProjectTeam activeTeam(ServiceDaos d, long projectId, long teamId) {
        ProjectTeam team = d.collaboration().findTeamForUpdate(teamId).orElseThrow(() -> new NotFoundException("Team does not exist"));
        if (!team.projectId().equals(projectId)) throw new NotFoundException("Team does not exist in project");
        if (team.status() != TeamStatus.ACTIVE) throw new ConflictException("Archived team is read-only");
        return team;
    }
    private static boolean isActiveLead(ServiceDaos d, long teamId, Long actorId) {
        return d.collaboration().findTeam(teamId).filter(t -> t.status() == TeamStatus.ACTIVE && t.leadUserId().equals(actorId))
                .filter(t -> d.members().find(t.projectId(), actorId)
                        .map(m -> m.status() == MembershipStatus.ACTIVE).orElse(false))
                .flatMap(t -> d.collaboration().findTeamMember(teamId, actorId))
                .map(m -> m.status() == MembershipStatus.ACTIVE).orElse(false);
    }
    private void requireManagerOrLead(ServiceDaos d, ProjectTeam team, Long actorId) {
        activeMember(d, team.projectId(), id(actorId, "actorId"));
        if (!activeManager(d, team.projectId(), actorId) && !isActiveLead(d, team.id(), actorId))
            throw new ForbiddenException("Project manager or team lead access is required");
    }
    private void activeTeamMember(ServiceDaos d, ProjectTeam team, long userId) {
        activeMember(d, team.projectId(), userId);
        TeamMember member = d.collaboration().findTeamMember(team.id(), userId)
                .orElseThrow(() -> new ForbiddenException("Assignee must belong to this team"));
        if (member.status() != MembershipStatus.ACTIVE) throw new ForbiddenException("Assignee must belong to this team");
    }
    private WorkTask requireTask(ServiceDaos d, long projectId, long taskId) {
        WorkTask value = d.collaboration().findTask(taskId).orElseThrow(() -> new NotFoundException("Task does not exist"));
        if (!value.projectId().equals(projectId)) throw new NotFoundException("Task does not exist in project");
        return value;
    }
    private WorkTask lockedTask(ServiceDaos d, long projectId, long taskId) {
        WorkTask value = d.collaboration().findTaskForUpdate(taskId).orElseThrow(() -> new NotFoundException("Task does not exist"));
        if (!value.projectId().equals(projectId)) throw new NotFoundException("Task does not exist in project");
        return value;
    }
    private static void requireVersion(Integer actual, Integer expected) {
        if (!Objects.equals(actual, expected)) throw new ConflictException("Task or team version is stale");
    }
    private static MemberView view(ServiceDaos d, ProjectMember member) {
        User user = d.users().findById(member.userId()).orElseThrow(() -> new NotFoundException("Member user does not exist"));
        return new MemberView(user.id(), user.username(), user.displayName(), member.projectRole(), member.status(), user.status());
    }
    private static WorkTask copy(WorkTask old, Long assignee, WorkTaskStatus status, Long acceptedBy, LocalDateTime acceptedAt) {
        return new WorkTask(old.id(), old.projectId(), old.teamId(), old.title(), old.description(), assignee,
                old.createdBy(), status, acceptedBy, acceptedAt, old.createdAt(), old.updatedAt(), old.lockVersion());
    }
    private static void event(ServiceDaos d, WorkTask task, long actorId, WorkTaskEventType type,
                              WorkTaskStatus previousStatus, Long previousAssignee, String note) {
        d.collaboration().appendEvent(new WorkTaskEvent(null, task.id(), actorId, type, previousStatus,
                task.status(), previousAssignee, task.assigneeUserId(), note, null));
    }
}
