package sg.annualva.submission;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The VA Submission tables. Constant SQL, named parameters (as-2). */
@Repository
class SubmissionStore {

    /** Where a VA Submission is: its System and its state as text, so a state no constant names yet can still be read. */
    record Located(UUID systemId, String state) {}

    /** What a VA Submission is as the table holds it. */
    record Row(
            UUID id, UUID systemId, int cycleYear, int quarter, SubmissionState state, boolean overdue,
            boolean endorsedLate, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, Instant startedAt) {}

    /** One VA Submission per System and Cycle: a second start, even a concurrent one, inserts nothing. */
    private static final String INSERT =
            "INSERT INTO va_submission (system_id, cycle_year, quarter, state, submit_due, review_due, endorse_due, "
            + "started_at, started_by) VALUES (:system, :cycle, :quarter, :state, :submitDue, :reviewDue, :endorseDue, "
            + ":now, :by) ON CONFLICT (system_id, cycle_year) DO NOTHING RETURNING id";

    private static final String INSERT_PARTICIPATION =
            "INSERT INTO submission_participation (submission_id, user_id, step, first_at) "
            + "VALUES (:submission, :user, :step, :now) ON CONFLICT DO NOTHING";

    private static final String COLUMNS =
            "SELECT id, system_id, cycle_year, quarter, state, overdue, endorsed_late, submit_due, review_due, "
            + "endorse_due, started_at FROM va_submission ";

    private static final String FIND = COLUMNS + "WHERE id = :id";

    private static final String IN_CYCLE =
            COLUMNS + "WHERE cycle_year = :cycle AND system_id = ANY(CAST(:systems AS uuid[]))";

    private static final String LOCATE = "SELECT system_id, state FROM va_submission WHERE id = :id";

    private static final String PARTICIPATION =
            "SELECT user_id, step, first_at FROM submission_participation WHERE submission_id = :id "
            + "ORDER BY first_at, step, user_id";

    private final JdbcClient jdbc;

    SubmissionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the VA Submission, or returns nothing when the System already has one in this Cycle. */
    Optional<UUID> insert(UUID systemId, int cycleYear, int quarter, SubmissionState state, LocalDate submitDue,
            LocalDate reviewDue, LocalDate endorseDue, Instant now, UUID by) {
        return jdbc.sql(INSERT)
                .param("system", systemId)
                .param("cycle", cycleYear)
                .param("quarter", quarter)
                .param("state", state.name())
                .param("submitDue", submitDue)
                .param("reviewDue", reviewDue)
                .param("endorseDue", endorseDue)
                .param("now", utc(now))
                .param("by", by)
                .query(UUID.class)
                .optional();
    }

    /** Records that the person acted at the step; the first time only, so the record keeps when they first did. */
    void recordParticipation(UUID submissionId, UUID userId, SubmissionStep step, Instant now) {
        jdbc.sql(INSERT_PARTICIPATION)
                .param("submission", submissionId)
                .param("user", userId)
                .param("step", step.name())
                .param("now", utc(now))
                .update();
    }

    Optional<Located> locate(UUID id) {
        return jdbc.sql(LOCATE).param("id", id)
                .query((rs, row) -> new Located(rs.getObject("system_id", UUID.class), rs.getString("state")))
                .optional();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query((rs, row) -> row(rs)).optional();
    }

    /** The VA Submissions of these Systems in one Cycle. */
    List<Row> inCycle(Collection<UUID> systemIds, int cycleYear) {
        if (systemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(IN_CYCLE)
                .param("cycle", cycleYear)
                .param("systems", systemIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, row) -> row(rs))
                .list();
    }

    List<Submissions.Acted> participation(UUID submissionId) {
        return jdbc.sql(PARTICIPATION).param("id", submissionId).query((rs, row) -> new Submissions.Acted(
                SubmissionStep.valueOf(rs.getString("step")), rs.getObject("user_id", UUID.class),
                rs.getObject("first_at", OffsetDateTime.class).toInstant())).list();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class), rs.getInt("cycle_year"),
                rs.getInt("quarter"), SubmissionState.valueOf(rs.getString("state")), rs.getBoolean("overdue"),
                rs.getBoolean("endorsed_late"), rs.getObject("submit_due", LocalDate.class),
                rs.getObject("review_due", LocalDate.class), rs.getObject("endorse_due", LocalDate.class),
                rs.getObject("started_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
