package sg.annualva.submission;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The VA Submission tables and the Declaration. Constant SQL, named parameters (as-2). */
@Repository
class SubmissionStore {

    /** Where a VA Submission is: its System and its state as text, so a state no constant names yet can still be read. */
    record Located(UUID systemId, String state) {}

    /** What a VA Submission is as the table holds it. */
    record Row(
            UUID id, UUID systemId, int cycleYear, int quarter, SubmissionState state, boolean overdue,
            boolean endorsedLate, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, Instant startedAt,
            @Nullable Instant submittedAt) {}

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
            + "endorse_due, started_at, submitted_at FROM va_submission ";

    private static final String FIND = COLUMNS + "WHERE id = :id";

    private static final String IN_CYCLE =
            COLUMNS + "WHERE cycle_year = :cycle AND system_id = ANY(CAST(:systems AS uuid[]))";

    private static final String LOCATE = "SELECT system_id, state FROM va_submission WHERE id = :id";

    /**
     * A write to what the SMO/Tech Lead prepares holds the VA Submission's row shared until it commits, so a submit (which
     * holds it exclusively) waits for the write, and a write that comes after the submit waits for it and then reads the
     * state the submit left.
     */
    private static final String LOCK_SHARED = LOCATE + " FOR SHARE";

    /**
     * A submit holds the row against every write to what the SMO/Tech Lead prepares until it commits. NO KEY: the rows
     * that refer to the VA Submission (a file, a Finding) can still be inserted meanwhile, by the scan job.
     */
    private static final String LOCK_EXCLUSIVE = LOCATE + " FOR NO KEY UPDATE";

    private static final String SUBMITTED =
            "UPDATE va_submission SET state = :state, submitted_at = :now, submitted_by = :by WHERE id = :id";

    private static final String PARTICIPATION =
            "SELECT user_id, step, first_at FROM submission_participation WHERE submission_id = :id "
            + "ORDER BY first_at, step, user_id";

    /** The current wording: the highest version (a new wording is a new version, V19). */
    private static final String CURRENT_DECLARATION_TEXT =
            "SELECT version, text, placeholder FROM declaration_text ORDER BY version DESC LIMIT 1";

    /** A snapshot of the wording accepted, copied from its version in the one statement. */
    private static final String INSERT_DECLARATION =
            "INSERT INTO declaration (submission_id, version, text, accepted_by, accepted_at) "
            + "SELECT :submission, version, text, :by, :now FROM declaration_text WHERE version = :version";

    private static final String LATEST_DECLARATION =
            "SELECT version, text, accepted_by, accepted_at FROM declaration WHERE submission_id = :submission "
            + "ORDER BY seq DESC LIMIT 1";

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
        return located(LOCATE, id);
    }

    /** The VA Submission's System and state, its row held shared until the transaction ends, for a write to its content. */
    Optional<Located> lockForEdit(UUID id) {
        return located(LOCK_SHARED, id);
    }

    /** The VA Submission's System and state, its row held against every such write until the transaction ends. */
    Optional<Located> lockForSubmit(UUID id) {
        return located(LOCK_EXCLUSIVE, id);
    }

    /** Moves a VA Submission whose row the caller holds ({@link #lockForSubmit}) to the state, as submitted by this person now. */
    void submitted(UUID id, SubmissionState state, UUID by, Instant now) {
        jdbc.sql(SUBMITTED).param("id", id).param("state", state.name()).param("by", by).param("now", utc(now)).update();
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
                instant(rs, "first_at"))).list();
    }

    /** The current Declaration wording; V19 seeds a version, so there always is one. */
    Declarations.Text currentDeclarationText() {
        return jdbc.sql(CURRENT_DECLARATION_TEXT)
                .query((rs, row) -> new Declarations.Text(rs.getInt("version"), rs.getString("text"),
                        rs.getBoolean("placeholder")))
                .single();
    }

    /** Keeps the wording of this version as accepted by the person now. */
    void acceptDeclaration(UUID submissionId, int version, UUID by, Instant now) {
        jdbc.sql(INSERT_DECLARATION)
                .param("submission", submissionId)
                .param("version", version)
                .param("by", by)
                .param("now", utc(now))
                .update();
    }

    /** The Declaration accepted at the latest submit of the VA Submission; nothing before its first. */
    Optional<Submissions.AcceptedDeclaration> latestDeclaration(UUID submissionId) {
        return jdbc.sql(LATEST_DECLARATION).param("submission", submissionId)
                .query((rs, row) -> new Submissions.AcceptedDeclaration(rs.getInt("version"), rs.getString("text"),
                        rs.getObject("accepted_by", UUID.class), instant(rs, "accepted_at")))
                .optional();
    }

    private Optional<Located> located(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id)
                .query((rs, row) -> new Located(rs.getObject("system_id", UUID.class), rs.getString("state")))
                .optional();
    }

    private static Row row(ResultSet rs) throws SQLException {
        OffsetDateTime submittedAt = rs.getObject("submitted_at", OffsetDateTime.class);
        return new Row(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class), rs.getInt("cycle_year"),
                rs.getInt("quarter"), SubmissionState.valueOf(rs.getString("state")), rs.getBoolean("overdue"),
                rs.getBoolean("endorsed_late"), rs.getObject("submit_due", LocalDate.class),
                rs.getObject("review_due", LocalDate.class), rs.getObject("endorse_due", LocalDate.class),
                instant(rs, "started_at"), submittedAt == null ? null : submittedAt.toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
