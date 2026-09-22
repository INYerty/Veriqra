package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record TestCase(
        Long id,
        Long projectId,
        Long keyNo,
        String title,
        String description,
        String preconditions,
        Priority priority,
        TestCaseStatus status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer lockVersion) { }
