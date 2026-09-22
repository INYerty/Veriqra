package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.time.LocalDateTime;
public record TestPlanResponse(Long id, Long projectId, Long keyNo, String name, String description,
        TestPlanStatus status, Integer version, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static TestPlanResponse from(TestPlan v) {
        return new TestPlanResponse(v.id(), v.projectId(), v.keyNo(), v.name(), v.description(),
                v.status(), v.lockVersion(), v.createdAt(), v.updatedAt());
    }
}
