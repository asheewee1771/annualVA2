package sg.annualva.parser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads the date a report says its scan was made. A report is untrusted, so the date is only ever a suggestion for the
 * SMO/Tech Lead to confirm: it is read in every form the tools write, and anything else, or a date no scan was made on and
 * the database cannot hold, is no date.
 */
public final class ReportDates {

    private static final Instant EARLIEST = Instant.parse("1990-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T00:00:00Z");
    private static final List<DateTimeFormatter> WITH_OFFSET = List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HHmm", "Z")
                    .toFormatter());

    private ReportDates() {}

    /**
     * The instant a report date names: an ISO date and time with an offset (written +08:00, as an XML report does, or +0800,
     * as a JSON one does, or Z), or without one (taken as UTC). Anything else, a year before 1990 or after 9999 (the
     * database cannot hold the latter, and a write that fails is retried for ever), is no date.
     */
    public static @Nullable Instant parse(@Nullable String raw) {
        String text = ReportText.clean(raw).orElse(null);
        if (text == null) {
            return null;
        }
        Instant instant = instantOf(text);
        return instant != null && !instant.isBefore(EARLIEST) && !instant.isAfter(LATEST) ? instant : null;
    }

    private static @Nullable Instant instantOf(String text) {
        for (DateTimeFormatter formatter : WITH_OFFSET) {
            try {
                return OffsetDateTime.parse(text, formatter).toInstant();
            } catch (DateTimeParseException notThisOne) {
                // try the next form
            }
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException notDateTime) {
            return null;
        }
    }
}
