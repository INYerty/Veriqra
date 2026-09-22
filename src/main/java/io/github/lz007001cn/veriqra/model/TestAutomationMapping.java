package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** One identity has at most one mapping row, including inactive mappings. */
public record TestAutomationMapping(Long id, Long automationIdentityId, Long testCaseId,
                                    AutomationMappingStatus status, Long createdBy, LocalDateTime createdAt,
                                    LocalDateTime updatedAt, Integer lockVersion) { }
