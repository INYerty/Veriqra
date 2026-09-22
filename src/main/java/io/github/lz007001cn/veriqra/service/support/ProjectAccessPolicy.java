package io.github.lz007001cn.veriqra.service.support;

import io.github.lz007001cn.veriqra.dao.*;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.exception.*;

/** Database-backed authorization rules; never trusts caller-supplied roles. */
public final class ProjectAccessPolicy {
    public User requireActiveUser(UserDao users, Long actorUserId) {
        if (actorUserId == null) throw new ForbiddenException("An authenticated actor is required");
        User actor = users.findById(actorUserId).orElseThrow(() -> new ForbiddenException("Actor is unavailable"));
        if (actor.status() != UserStatus.ACTIVE) throw new ForbiddenException("Actor is disabled");
        return actor;
    }

    public User requireAdmin(UserDao users, Long actorUserId) {
        User actor = requireActiveUser(users, actorUserId);
        if (actor.systemRole() != SystemRole.ADMIN) throw new ForbiddenException("Platform administrator access is required");
        return actor;
    }

    public User requireAdminForWrite(UserDao users, Long actorUserId) {
        User actor = requireActiveLockedUser(users, actorUserId);
        if (actor.systemRole() != SystemRole.ADMIN) throw new ForbiddenException("Platform administrator access is required");
        return actor;
    }

    public User requireProjectRead(UserDao users, ProjectMemberDao members, Long actorUserId, Long projectId) {
        User actor = requireActiveUser(users, actorUserId);
        if (actor.systemRole() == SystemRole.ADMIN) return actor;
        ProjectMember member = members.find(projectId, actorUserId)
                .orElseThrow(() -> new ForbiddenException("Active project membership is required"));
        if (member.status() != MembershipStatus.ACTIVE) throw new ForbiddenException("Active project membership is required");
        return actor;
    }

    public User requireAssetWrite(UserDao users, ProjectMemberDao members, Long actorUserId, Long projectId) {
        User actor = requireActiveLockedUser(users, actorUserId);
        if (actor.systemRole() == SystemRole.ADMIN) return actor;
        ProjectMember member = members.findForShare(projectId, actorUserId)
                .orElseThrow(() -> new ForbiddenException("Project TESTER access is required"));
        if (member.status() != MembershipStatus.ACTIVE || member.projectRole() != ProjectRole.TESTER) {
            throw new ForbiddenException("Project TESTER access is required");
        }
        return actor;
    }

    /** Active ADMIN or any ACTIVE project member; used by Defect reporting/processing only. */
    public User requireProjectMemberWrite(UserDao users, ProjectMemberDao members,
                                          Long actorUserId, Long projectId) {
        User actor = requireActiveLockedUser(users, actorUserId);
        if (actor.systemRole() == SystemRole.ADMIN) return actor;
        ProjectMember member = members.findForShare(projectId, actorUserId)
                .orElseThrow(() -> new ForbiddenException("Active project membership is required"));
        if (member.status() != MembershipStatus.ACTIVE) {
            throw new ForbiddenException("Active project membership is required");
        }
        return actor;
    }

    private User requireActiveLockedUser(UserDao users, Long actorUserId) {
        if (actorUserId == null) throw new ForbiddenException("An authenticated actor is required");
        User actor = users.findByIdForShare(actorUserId)
                .orElseThrow(() -> new ForbiddenException("Actor is unavailable"));
        if (actor.status() != UserStatus.ACTIVE) throw new ForbiddenException("Actor is disabled");
        return actor;
    }
}
