package sg.annualva.cycle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the calendar cannot derive about a VA Cycle (ADR 0015 item 1, ADR 0044): that the lifecycle job recorded its
 * opening, and its close, with an early close's date. Constant SQL, named parameters (as-2). The submission module's
 * lifecycle writes it; the calendar reads it into {@link VaCycle#closedAt()} and {@link VaCycle#closesOn()}.
 */
@Repository
public class CycleStore {

    /** A Cycle's close as stored: an early close's date, null while the default stands, and when it closed. */
    record Close(@Nullable LocalDate closesOn, Instant closedAt) {}

    private static final String OPEN =
            "INSERT INTO va_cycle (year, opened_at) VALUES (:year, :now) ON CONFLICT (year) DO NOTHING";

    private static final String CLOSE =
            "UPDATE va_cycle SET closes_on = CAST(:closesOn AS date), closed_at = :now, closed_by = CAST(:by AS uuid) "
            + "WHERE year = :year AND closed_at IS NULL";

    private static final String CLOSED = "SELECT year, closes_on, closed_at FROM va_cycle WHERE closed_at IS NOT NULL";

    private final JdbcClient jdbc;

    CycleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records that the Cycle's Q1 has started; whether this call recorded it (false when it was already). */
    public boolean open(int year, Instant now) {
        return jdbc.sql(OPEN).param("year", year).param("now", utc(now)).update() == 1;
    }

    /**
     * Records the close of an open Cycle the caller has opened ({@link #open}) and holds against every other close.
     *
     * @param closesOn an early close's business date, or null to keep the default
     * @param by the VA POC who closed it, or null for the lifecycle job
     */
    public void close(int year, @Nullable LocalDate closesOn, Instant now, @Nullable UUID by) {
        int closed = jdbc.sql(CLOSE).param("year", year).param("closesOn", closesOn).param("now", utc(now))
                .param("by", by).update();
        if (closed != 1) {
            throw new IllegalStateException("a close is recorded once, for a Cycle that was opened");
        }
    }

    /** The closed Cycles, by year. */
    Map<Integer, Close> closes() {
        return jdbc.sql(CLOSED).query((rs, row) -> Map.entry(rs.getInt("year"), new Close(
                        rs.getObject("closes_on", LocalDate.class),
                        rs.getObject("closed_at", OffsetDateTime.class).toInstant())))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
