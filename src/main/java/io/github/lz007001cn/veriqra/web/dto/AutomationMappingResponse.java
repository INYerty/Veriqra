package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.*;
import java.time.LocalDateTime;

public record AutomationMappingResponse(Long id, Long automationIdentityId, Long testCaseId,
                                        AutomationMappingStatus status, Long createdBy, LocalDateTime createdAt,
                                        LocalDateTime updatedAt, Integer version) {
    public static AutomationMappingResponse from(TestAutomationMapping value) {
        return new AutomationMappingResponse(value.id(), value.automationIdentityId(), value.testCaseId(),
                value.status(), value.createdBy(), value.createdAt(), value.updatedAt(), value.lockVersion());
    }
}
