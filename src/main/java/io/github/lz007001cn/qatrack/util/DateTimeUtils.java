package io.github.lz007001cn.qatrack.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Thread-safe text conversion; no time-zone conversion or JDBC serialization. */
public final class DateTimeUtils {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private DateTimeUtils() { }

    /** Formats a non-null date as year-month-day. */
    public static String formatDate(LocalDate value) { return DATE.format(value); }

    /** Parses a non-null date strictly; invalid text propagates DateTimeParseException. */
    public static LocalDate parseDate(String text) { return LocalDate.parse(text, DATE); }

    /** Formats through seconds, omitting fractional seconds; does not change the input. */
    public static String formatDateTime(LocalDateTime value) { return DATE_TIME.format(value); }

    /** Parses through seconds with zero nanos; invalid text propagates DateTimeParseException. */
    public static LocalDateTime parseDateTime(String text) { return LocalDateTime.parse(text, DATE_TIME); }
}
