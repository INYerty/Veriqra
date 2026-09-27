package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

public record ProjectTeam(Long id, Long projectId, String name, Long leadUserId,
                          TeamStatus status, Long createdBy, LocalDateTime createdAt,
                          LocalDateTime updatedAt, Integer lockVersion) { }
