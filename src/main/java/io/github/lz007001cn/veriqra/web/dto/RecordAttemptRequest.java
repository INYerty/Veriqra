package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.*;
public record RecordAttemptRequest(TestAttemptStatus outcome, Long durationMs, String comment, String failureMessage, UUID submissionKey) {
    public RecordAttemptRequest {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(submissionKey, "submissionKey");
    }
}
