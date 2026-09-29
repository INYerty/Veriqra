package io.github.lz007001cn.veriqra.admin;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;

public record CreditEntry(long id, long userId, @JsonSerialize(using=ToStringSerializer.class) long amount,
                          String type, long actorUserId,
                          String reason, String batchId, LocalDateTime createdAt,
                          Long projectId, String transferId, Long taskId, Long counterpartyUserId) { }
