package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record UpdateTestCaseRequest(String title, String description, String preconditions, Priority priority, TestCaseStatus status, Integer expectedVersion, java.util.List<TestStepRequest> steps) {
    public UpdateTestCaseRequest {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        Objects.requireNonNull(steps, "steps");
        steps = java.util.List.copyOf(steps);
    }

}
