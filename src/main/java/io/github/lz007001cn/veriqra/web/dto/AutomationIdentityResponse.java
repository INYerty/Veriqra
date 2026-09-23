package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.*;
import java.time.LocalDateTime;

public record AutomationIdentityResponse(Long id, Long projectId, AutomationSource source, String namespace,
                                         String externalKey, LocalDateTime createdAt) {
    public static AutomationIdentityResponse from(TestAutomationIdentity value) {
        return new AutomationIdentityResponse(value.id(), value.projectId(), value.source(), value.namespace(),
                value.externalKey(), value.createdAt());
    }
}
