package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.TestAttemptStatus;
import java.util.UUID;

/** attemptNo, executedBy and timestamps are assigned by the Service/database. */
public record RecordAttemptCommand(Long runCaseId, TestAttemptStatus status, Long durationMs,
                                   String comment, String failureMessage, UUID submissionKey) { }
