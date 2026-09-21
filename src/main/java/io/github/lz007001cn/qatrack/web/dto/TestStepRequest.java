package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record TestStepRequest(Integer stepOrder, String action, String expectedResult) {
    public TestStepRequest {
        Objects.requireNonNull(stepOrder, "stepOrder");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(expectedResult, "expectedResult");
    }
    public io.github.lz007001cn.qatrack.service.command.TestStepInput toInput() {
        return new io.github.lz007001cn.qatrack.service.command.TestStepInput(stepOrder, action, expectedResult);
    }
}
