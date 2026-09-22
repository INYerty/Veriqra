package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record ProjectMember(
        Long projectId,
        Long userId,
        ProjectRole projectRole,
        MembershipStatus status,
        LocalDateTime joinedAt,
        LocalDateTime updatedAt,
        Integer lockVersion) { }
