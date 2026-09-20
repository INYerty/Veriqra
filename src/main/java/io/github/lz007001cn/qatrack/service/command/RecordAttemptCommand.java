package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.TestAttemptStatus;
import java.util.UUID;

/** attemptNo, executedBy and timestamps are assigned by the Service/database. */
public record RecordAttemptCommand(Long runCaseId, TestAttemptStatus status, Long durationMs,
                                   String comment, String failureMessage, UUID submissionKey) { }
