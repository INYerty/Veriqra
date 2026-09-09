package io.github.lz007001cn.qatrack.model;

import java.time.LocalDateTime;

/** Stable external identity; namespace/key are supplied, never parsed or normalized here. */
public record TestAutomationIdentity(Long id, Long projectId, AutomationSource source,
                                     String namespace, String externalKey, LocalDateTime createdAt) { }
