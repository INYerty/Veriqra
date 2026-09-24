package io.github.lz007001cn.veriqra.admin;

import java.time.LocalDateTime;

public record AccessEvent(long id, Long userId, String username, String ipAddress, String userAgent, String browser,
                          String operatingSystem, String deviceType, String httpMethod,
                          String requestPath, int statusCode, String requestId, int durationMs,
                          LocalDateTime createdAt) { }
