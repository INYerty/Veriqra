package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record CreateTestPlanRequest(String name, String description, java.util.List<Long> testCaseIds) {
    public CreateTestPlanRequest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(testCaseIds, "testCaseIds");
        testCaseIds = java.util.List.copyOf(testCaseIds);
    }

}
