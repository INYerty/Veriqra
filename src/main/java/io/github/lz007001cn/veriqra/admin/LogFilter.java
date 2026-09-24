package io.github.lz007001cn.veriqra.admin;

import java.time.LocalDateTime;

/** Optional, bounded admin log filters. Query builders only interpolate fixed column names. */
public record LogFilter(LocalDateTime from, LocalDateTime to, String username, String result,
                        String ip, String deviceType, Integer statusCode, String method,
                        String action, Long actorId) { }
