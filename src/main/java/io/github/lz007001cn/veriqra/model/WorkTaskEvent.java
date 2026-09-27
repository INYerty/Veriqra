package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Append-only business history. No DAO update/delete operation exists. */
public record WorkTaskEvent(Long id, Long taskId, Long actorUserId, WorkTaskEventType eventType,
                            WorkTaskStatus fromStatus, WorkTaskStatus toStatus,
                            Long fromAssigneeUserId, Long toAssigneeUserId,
                            String note, LocalDateTime createdAt) { }
