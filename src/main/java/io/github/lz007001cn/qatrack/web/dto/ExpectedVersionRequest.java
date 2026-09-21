package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.util.Objects;
/** Only caller-editable fields; missing/null required input is rejected during JSON construction. */
public record ExpectedVersionRequest(Integer expectedVersion) {
    public ExpectedVersionRequest {
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    }

}
