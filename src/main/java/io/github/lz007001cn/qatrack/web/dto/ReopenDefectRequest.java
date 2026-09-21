package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.util.*;
public record ReopenDefectRequest(Integer expectedVersion, Long failureAttemptId, Long assigneeId) {
    public ReopenDefectRequest {
        new ExpectedVersionRequest(expectedVersion);
        Objects.requireNonNull(failureAttemptId, "failureAttemptId");
        Objects.requireNonNull(assigneeId, "assigneeId");
    }
}
