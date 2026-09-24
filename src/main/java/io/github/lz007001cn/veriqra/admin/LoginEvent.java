package io.github.lz007001cn.veriqra.admin;

import java.time.LocalDateTime;

public record LoginEvent(long id, Long userId, String usernameAttempted, String ipAddress,
                         String userAgent, String browser, String operatingSystem, String deviceType,
                         String result, String failureReason, LocalDateTime createdAt) { }
