package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.util.*;
public record CreateDefectRequest(Long failureAttemptId, String title, String description, DefectSeverity severity, Priority priority, Long assigneeId) {
    public CreateDefectRequest {
        Objects.requireNonNull(failureAttemptId, "failureAttemptId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(priority, "priority");
    }
}
