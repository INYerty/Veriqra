package io.github.lz007001cn.veriqra.admin;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;

public record CreditAccountView(long userId, String username, String displayName, String status,
                                @JsonSerialize(using=ToStringSerializer.class) long balance,
                                LocalDateTime updatedAt, int lockVersion) { }
