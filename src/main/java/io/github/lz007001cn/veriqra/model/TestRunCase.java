package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Immutable execution snapshot, independent of the current editable TestCase. */
public record TestRunCase(
        Long id,
        Long testRunId,
        Long testCaseId,
        String snapshotTitle,
        String snapshotDescription,
        String snapshotPreconditions,
        Priority snapshotPriority,
        LocalDateTime capturedAt) { }
