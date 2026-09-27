package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** A scoped project appointment, never a platform account role. */
public record ProjectManager(Long projectId, Long userId, Long appointedBy,
                             MembershipStatus status, LocalDateTime appointedAt, LocalDateTime updatedAt) { }
