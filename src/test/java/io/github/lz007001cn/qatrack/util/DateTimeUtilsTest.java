package io.github.lz007001cn.qatrack.util;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import static org.junit.jupiter.api.Assertions.*;

class DateTimeUtilsTest {
    @Test void dateFormatUsesFixedWidth() {
        assertEquals("2024-02-09", DateTimeUtils.formatDate(LocalDate.of(2024, 2, 9)));
    }

    @Test void dateParseAcceptsLeapDay() {
        assertEquals(LocalDate.of(2024, 2, 29), DateTimeUtils.parseDate("2024-02-29"));
    }

    @Test void dateTimeFormatOmitsFractionWithoutChangingValue() {
        var value = LocalDateTime.of(2024, 2, 29, 3, 4, 5, 123456000);
        assertEquals("2024-02-29 03:04:05", DateTimeUtils.formatDateTime(value));
        assertEquals(123456000, value.getNano());
    }

    @Test void dateTimeParseHasSecondPrecision() {
        assertEquals(LocalDateTime.of(2024, 2, 29, 3, 4, 5),
                DateTimeUtils.parseDateTime("2024-02-29 03:04:05"));
    }

    @Test void invalidInputIsRejectedWithoutNormalization() {
        for (String text : new String[]{"2023-02-29", "2024-04-31", "2024-2-09", "bad", ""}) {
            assertThrows(DateTimeParseException.class, () -> DateTimeUtils.parseDate(text));
        }
        for (String text : new String[]{"2024-02-29 24:00:00", "2024-02-29 03:04:60",
                "2023-02-29 03:04:05", "2024-02-29T03:04:05", "2024-02-29 03:04:05.123456"}) {
            assertThrows(DateTimeParseException.class, () -> DateTimeUtils.parseDateTime(text));
        }
    }
}
