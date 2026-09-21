package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.time.LocalDateTime;
import java.util.UUID;
public record AttemptResponse(Long id, Long runCaseId, Integer attemptNo, TestAttemptStatus outcome, Long executedBy, LocalDateTime executedAt, LocalDateTime recordedAt, Long durationMs, String comment, String failureMessage, UUID submissionKey) {
    public static AttemptResponse from(TestAttempt v) { return new AttemptResponse(v.id(), v.testRunCaseId(), v.attemptNo(), v.status(), v.executedBy(), v.executedAt(), v.recordedAt(), v.durationMs(), v.comment(), v.failureMessage(), v.submissionKey()); }
}
