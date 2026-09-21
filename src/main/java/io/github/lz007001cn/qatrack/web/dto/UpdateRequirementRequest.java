package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record UpdateRequirementRequest(String title, String description, Priority priority, RequirementStatus status, Integer expectedVersion) {
    public UpdateRequirementRequest {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    }

}
