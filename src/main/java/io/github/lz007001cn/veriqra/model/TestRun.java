package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Execution context; project and optional plan are immutable after creation. */
public record TestRun(
        Long id,
        Long projectId,
        Long testPlanId,
        String name,
        String environment,
        String buildVersion,
        TestRunStatus status,
        LocalDateTime endedAt,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer lockVersion) { }
