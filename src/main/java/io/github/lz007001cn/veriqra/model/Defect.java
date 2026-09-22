package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Defect row only; transition rules and authorization belong to the caller. */
public record Defect(
        Long id,
        Long projectId,
        Long keyNo,
        String title,
        String description,
        DefectSeverity severity,
        Priority priority,
        DefectStatus status,
        Long reporterId,
        Long assigneeId,
        String resolutionNote,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer lockVersion) { }
