package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record TestCaseRequirement(
        Long requirementId,
        Long testCaseId,
        TraceabilityStatus status,
        Long linkedBy,
        LocalDateTime linkedAt,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime updatedAt) { }
