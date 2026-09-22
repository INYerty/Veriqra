package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Immutable projects row; creation of counters/members belongs to a future Service transaction. */
public record Project(Long id, String projectKey, String name, String description,
                      ProjectStatus status, Long createdBy, LocalDateTime createdAt,
                      LocalDateTime updatedAt, Integer lockVersion) { }
