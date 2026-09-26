package io.github.lz007001cn.veriqra.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** Shanghai calendar-day bounds represented as UTC DATETIME values for the existing JDBC session. */
public record AdminTodayWindow(LocalDateTime startUtc, LocalDateTime nextStartUtc) {
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    public static AdminTodayWindow now(Clock clock) {
        LocalDate today = LocalDate.now(Objects.requireNonNull(clock).withZone(DISPLAY_ZONE));
        return new AdminTodayWindow(
                LocalDateTime.ofInstant(today.atStartOfDay(DISPLAY_ZONE).toInstant(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(today.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant(), ZoneOffset.UTC));
    }
}
