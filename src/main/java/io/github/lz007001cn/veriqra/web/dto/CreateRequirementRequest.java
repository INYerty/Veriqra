package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.Priority;

public record CreateRequirementRequest(String title, String description, Priority priority) {
    public CreateRequirementRequest {
        java.util.Objects.requireNonNull(title, "title");
        java.util.Objects.requireNonNull(priority, "priority");
    }
}
