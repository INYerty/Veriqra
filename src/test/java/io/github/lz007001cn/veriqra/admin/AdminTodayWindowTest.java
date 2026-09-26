package io.github.lz007001cn.veriqra.admin;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminTodayWindowTest {
    @Test void shanghaiDayStartsAtPreviousUtc1600AndIgnoresClockDefaultZone() {
        Instant instant = Instant.parse("2026-09-25T17:00:00Z");
        AdminTodayWindow utc = AdminTodayWindow.now(Clock.fixed(instant, ZoneOffset.UTC));
        AdminTodayWindow otherZone = AdminTodayWindow.now(Clock.fixed(instant, ZoneId.of("America/New_York")));
        assertEquals(utc, otherZone);
        assertEquals(LocalDateTime.parse("2026-09-25T16:00:00"), utc.startUtc());
        assertEquals(LocalDateTime.parse("2026-09-26T16:00:00"), utc.nextStartUtc());
    }

    @Test void instantImmediatelyBeforeShanghaiMidnightBelongsToPreviousDay() {
        AdminTodayWindow window = AdminTodayWindow.now(Clock.fixed(
                Instant.parse("2026-09-25T15:59:59Z"), ZoneOffset.UTC));
        assertEquals(LocalDateTime.parse("2026-09-24T16:00:00"), window.startUtc());
        assertEquals(LocalDateTime.parse("2026-09-25T16:00:00"), window.nextStartUtc());
    }
}
