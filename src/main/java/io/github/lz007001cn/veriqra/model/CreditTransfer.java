package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record CreditTransfer(String id, Long projectId, Long senderUserId, Long recipientUserId,
                             @JsonSerialize(using = ToStringSerializer.class) Long amount,
                             String kind, Long offerId, String note, LocalDateTime createdAt) { }
