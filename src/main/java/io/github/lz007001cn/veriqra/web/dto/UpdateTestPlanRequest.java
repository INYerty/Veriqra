package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record UpdateTestPlanRequest(String name, String description, TestPlanStatus status, Integer expectedVersion) {
    public UpdateTestPlanRequest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    }

}
