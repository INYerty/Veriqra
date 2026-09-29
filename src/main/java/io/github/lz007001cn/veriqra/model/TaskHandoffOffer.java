package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record TaskHandoffOffer(Long id, String requestKey, Long projectId, Long taskId,
                               Long fromUserId, Long toUserId, @JsonSerialize(using = ToStringSerializer.class) Long creditAmount, String note,
                               String status, LocalDateTime createdAt, LocalDateTime resolvedAt) { }
