package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.model.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;

/** Safe admin projection; deliberately excludes password hash. */
public record AdminUserView(long id, String username, String displayName, SystemRole systemRole,
                            UserStatus status, int lockVersion, LocalDateTime createdAt,
                            LocalDateTime lastLogin, LocalDateTime lastSeen, String lastIp,
                            String lastDevice, @JsonSerialize(using=ToStringSerializer.class) long creditBalance) { }
