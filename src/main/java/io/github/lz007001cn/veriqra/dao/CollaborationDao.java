package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.*;
import java.util.List;
import java.util.Optional;

/** Connection-scoped persistence only; authorization and workflow belong to the Service. */
public interface CollaborationDao {
    Optional<ProjectManager> findManager(long projectId, long userId);
    List<ProjectManager> listManagers(long projectId);
    ProjectManager appointManager(long projectId, long userId, long actorId);
    void revokeManager(long projectId, long userId);

    ProjectTeam insertTeam(long projectId, String name, long leadId, long actorId);
    Optional<ProjectTeam> findTeam(long teamId);
    Optional<ProjectTeam> findTeamForUpdate(long teamId);
    List<ProjectTeam> listTeams(long projectId);
    ProjectTeam updateTeam(ProjectTeam value);
    Optional<TeamMember> findTeamMember(long teamId, long userId);
    List<TeamMember> listTeamMembers(long teamId);
    TeamMember setTeamMember(long teamId, long projectId, long userId, MembershipStatus status);

    WorkTask insertTask(long projectId, long teamId, String title, String description, long assigneeId, long actorId);
    Optional<WorkTask> findTask(long taskId);
    Optional<WorkTask> findTaskForUpdate(long taskId);
    List<WorkTask> listTasks(long projectId);
    WorkTask updateTask(WorkTask value);
    WorkTaskEvent appendEvent(WorkTaskEvent value);
    List<WorkTaskEvent> listEvents(long taskId);
}
