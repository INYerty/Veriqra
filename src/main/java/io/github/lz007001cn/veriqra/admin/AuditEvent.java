package io.github.lz007001cn.veriqra.admin;

import java.time.LocalDateTime;

public record AuditEvent(long id, long actorUserId, String action, String targetType, Long targetId,
                         String summary, String metadataJson, String ipAddress, String requestId,
                         LocalDateTime createdAt) { }
