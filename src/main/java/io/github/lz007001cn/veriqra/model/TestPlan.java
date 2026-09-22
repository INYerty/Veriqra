package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record TestPlan(
        Long id,
        Long projectId,
        Long keyNo,
        String name,
        String description,
        TestPlanStatus status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer lockVersion) { }
