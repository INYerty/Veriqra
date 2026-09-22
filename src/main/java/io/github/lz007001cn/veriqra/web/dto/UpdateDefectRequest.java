package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.*;
public record UpdateDefectRequest(String title, String description, DefectSeverity severity, Priority priority, Long assigneeId, Integer expectedVersion) {
    public UpdateDefectRequest {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(priority, "priority");
        new ExpectedVersionRequest(expectedVersion);
    }
}
