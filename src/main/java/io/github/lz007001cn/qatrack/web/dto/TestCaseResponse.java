package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import java.time.LocalDateTime;
public record TestCaseResponse(Long id, Long projectId, Long keyNo, String title, String description,
        String preconditions, Priority priority, TestCaseStatus status, Integer version,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static TestCaseResponse from(TestCase v) {
        return new TestCaseResponse(v.id(), v.projectId(), v.keyNo(), v.title(), v.description(),
                v.preconditions(), v.priority(), v.status(), v.lockVersion(), v.createdAt(), v.updatedAt());
    }
}
