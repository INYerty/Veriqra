package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;
import java.util.UUID;

/** Append-only result fact. Import/mapping IDs are references only; no import behavior. */
public record TestAttempt(
        Long id,
        Long testRunCaseId,
        Integer attemptNo,
        TestAttemptStatus status,
        Long executedBy,
        Long importId,
        Long automationMappingId,
        LocalDateTime executedAt,
        LocalDateTime recordedAt,
        Long durationMs,
        String comment,
        String failureMessage,
        UUID submissionKey) { }
