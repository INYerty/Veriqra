package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record CreateTestCaseRequest(String title, String description, String preconditions, Priority priority, java.util.List<TestStepRequest> steps) {
    public CreateTestCaseRequest {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(steps, "steps");
        steps = java.util.List.copyOf(steps);
    }

}
