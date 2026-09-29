package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record WorkTask(Long id, Long projectId, Long teamId, String title, String description,
                       @JsonSerialize(using = ToStringSerializer.class) Long rewardCredit,
                       Long assigneeUserId, Long createdBy, WorkTaskStatus status,
                       Long acceptedBy, LocalDateTime acceptedAt, LocalDateTime createdAt,
                       LocalDateTime updatedAt, Integer lockVersion) { }
