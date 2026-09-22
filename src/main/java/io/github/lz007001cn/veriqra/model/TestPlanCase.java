package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record TestPlanCase(
        Long testPlanId,
        Long testCaseId,
        Long addedBy,
        LocalDateTime addedAt) { }
