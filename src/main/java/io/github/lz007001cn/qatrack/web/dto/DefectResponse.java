package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.time.LocalDateTime;
import java.util.UUID;
public record DefectResponse(Long id, Long projectId, Long keyNo, String title, String description, DefectSeverity severity, Priority priority, DefectStatus status, Long reporterId, Long assigneeId, String resolutionNote, LocalDateTime createdAt, LocalDateTime updatedAt, Integer version) {
    public static DefectResponse from(Defect v) { return new DefectResponse(v.id(), v.projectId(), v.keyNo(), v.title(), v.description(), v.severity(), v.priority(), v.status(), v.reporterId(), v.assigneeId(), v.resolutionNote(), v.createdAt(), v.updatedAt(), v.lockVersion()); }
}
