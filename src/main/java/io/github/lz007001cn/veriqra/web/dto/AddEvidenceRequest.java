package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.*;
public record AddEvidenceRequest(Long failureAttemptId) {
    public AddEvidenceRequest {
        Objects.requireNonNull(failureAttemptId, "failureAttemptId");
    }
}
