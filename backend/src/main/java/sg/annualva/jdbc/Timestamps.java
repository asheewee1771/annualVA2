package sg.annualva.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * How every store hands an instant to PostgreSQL and reads one back: a {@code timestamptz} is written as the instant at
 * offset UTC, as every timestamp is stored (AGENTS.md), and read back as the instant it names.
 */
public final class Timestamps {

    private Timestamps() {}

    /** The instant as a {@code timestamptz} parameter, at offset UTC. */
    public static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** As {@link #utc}, for an optional parameter: null stays null. */
    public static @Nullable OffsetDateTime utcOrNull(@Nullable Instant instant) {
        return instant == null ? null : utc(instant);
    }

    /** A {@code timestamptz} column of the current row as an instant; null when the column is null. */
    public static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
