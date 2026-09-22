package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.time.LocalDateTime;
import java.util.UUID;
public record RunResponse(Long id, Long projectId, Long testPlanId, String name, String environment, String buildVersion, TestRunStatus status, LocalDateTime endedAt, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt, Integer version) {
    public static RunResponse from(TestRun v) { return new RunResponse(v.id(), v.projectId(), v.testPlanId(), v.name(), v.environment(), v.buildVersion(), v.status(), v.endedAt(), v.createdBy(), v.createdAt(), v.updatedAt(), v.lockVersion()); }
}
