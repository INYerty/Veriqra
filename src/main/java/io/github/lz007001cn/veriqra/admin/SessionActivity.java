package io.github.lz007001cn.veriqra.admin;

import java.time.LocalDateTime;

/** A login event plus the user's latest observable request; not a live session. */
public record SessionActivity(long loginEventId,Long userId,String username,LocalDateTime loginTime,
                              LocalDateTime userLastSeen,String ipAddress,String deviceType) { }
