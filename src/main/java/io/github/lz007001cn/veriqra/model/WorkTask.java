package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

public record WorkTask(Long id, Long projectId, Long teamId, String title, String description,
                       Long assigneeUserId, Long createdBy, WorkTaskStatus status,
                       Long acceptedBy, LocalDateTime acceptedAt, LocalDateTime createdAt,
                       LocalDateTime updatedAt, Integer lockVersion) { }
