package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.model.*;
import java.util.List;

public interface CollaborationService {
    record MemberView(Long userId, String username, String displayName, ProjectRole projectRole,
                      MembershipStatus status, UserStatus userStatus) { }
    record TaskDetail(WorkTask task, List<WorkTaskEvent> events) { }

    List<ProjectManager> listManagers(Long actorId, Long projectId);
    ProjectManager appointManager(Long actorId, Long projectId, Long memberId);
    void revokeManager(Long actorId, Long projectId, Long memberId);
    List<MemberView> listProjectMembers(Long actorId, Long projectId);
    MemberView addProjectMember(Long actorId, Long projectId, String username, ProjectRole role);

    List<ProjectTeam> listTeams(Long actorId, Long projectId);
    ProjectTeam createTeam(Long actorId, Long projectId, String name, Long leadUserId);
    ProjectTeam changeTeamLead(Long actorId, Long projectId, Long teamId, Long leadUserId, Integer expectedVersion);
    List<TeamMember> listTeamMembers(Long actorId, Long projectId, Long teamId);
    TeamMember setTeamMember(Long actorId, Long projectId, Long teamId, Long memberId, MembershipStatus status);

    List<WorkTask> listTasks(Long actorId, Long projectId);
    TaskDetail getTask(Long actorId, Long projectId, Long taskId);
    WorkTask createTask(Long actorId, Long projectId, Long teamId, String title, String description, Long assigneeId);
    WorkTask reassignTask(Long actorId, Long projectId, Long taskId, Long assigneeId, Integer expectedVersion);
    WorkTask transitionTask(Long actorId, Long projectId, Long taskId, WorkTaskStatus next,
                            String note, Integer expectedVersion);
}
