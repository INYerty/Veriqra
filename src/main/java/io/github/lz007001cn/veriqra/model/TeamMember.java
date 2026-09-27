package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

public record TeamMember(Long teamId, Long projectId, Long userId, MembershipStatus status,
                         LocalDateTime joinedAt, LocalDateTime updatedAt) { }
