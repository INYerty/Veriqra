package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.*;
import java.time.LocalDateTime;

public record ProjectResponse(Long id, String projectKey, String name, String description,
                              ProjectStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static ProjectResponse from(Project p) {
        return new ProjectResponse(p.id(), p.projectKey(), p.name(), p.description(), p.status(), p.createdAt(), p.updatedAt());
    }
}
