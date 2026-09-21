package io.github.lz007001cn.qatrack.web.dto;

import io.github.lz007001cn.qatrack.model.*;
import java.time.LocalDateTime;

public record RequirementResponse(Long id, Long projectId, Long keyNo, String title, String description,
                                  Priority priority, RequirementStatus status, Integer version,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static RequirementResponse from(Requirement r) {
        return new RequirementResponse(r.id(), r.projectId(), r.keyNo(), r.title(), r.description(),
                r.priority(), r.status(), r.lockVersion(), r.createdAt(), r.updatedAt());
    }
}
