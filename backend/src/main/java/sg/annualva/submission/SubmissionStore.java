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
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.cycle.Quarter;

/** The VA Submission tables and the Declaration. Constant SQL, named parameters (as-2). */
@Repository
class SubmissionStore {

    /** Where a VA Submission is: its System and its state as text, so a state no constant names yet can still be read. */
    record Located(UUID systemId, String state) {}

    /** What a VA Submission is as the table holds it. */
    record Row(
            UUID id, UUID systemId, int cycleYear, int quarter, SubmissionState state, boolean overdue,
            boolean endorsedLate, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, Instant startedAt,
            @Nullable Instant submittedAt, @Nullable Instant overdueSince, @Nullable LocalDate dueOn) {}

    /**
     * What the Overdue sweep has just flagged: a VA Submission, or a System with none (Not Started, no {@code id}), with
     * the state it was in as the table holds it.
     */
    record Flagged(@Nullable UUID id, UUID systemId, String state) {}

    /** How a System with no VA Submission is named to the Overdue rule and in an audit entry. */
    static final String NOT_STARTED = "NOT_STARTED";

    /**
     * The one Overdue rule every statement asks (ADR 0033 item 4, V20): whether the window the state waits on has passed
     * on {@code :today}, for these windows. A state's window is never written out anywhere else.
     */
    private static String windowPassed(String state, String submitDue, String reviewDue, String endorseDue) {
        return "window_passed(" + state + ", " + submitDue + ", " + reviewDue + ", " + endorseDue + ", :today)";
    }

    /** The same, for the windows of the row being written. */
    private static String windowPassed(String state) {
        return windowPassed(state, "submit_due", "review_due", "endorse_due");
    }

    /**
     * What a move to the state {@code :state} does to the flag: it keeps it, and when it was set, only if the window the
     * new state waits on has passed as well, and otherwise clears it (the sweep sets it again when that window passes).
     * Every transition's UPDATE sets this, and nothing else touches {@code overdue_since}. An {@code UPDATE} reads the
     * row's old values, so the new state is the parameter.
     */
    static final String OVERDUE_AFTER_MOVE =
            "overdue_since = CASE WHEN " + windowPassed("CAST(:state AS text)") + " THEN overdue_since END";

    /**
     * One VA Submission per System and Cycle: a second start, even a concurrent one, inserts nothing. Starting is the move
     * from Not Started: the flag the sweep set on the System, and when, goes with it if its window has passed (ADR 0033).
     */
    private static final String INSERT =
            "INSERT INTO va_submission (system_id, cycle_year, quarter, state, submit_due, review_due, endorse_due, "
            + "started_at, started_by, overdue_since) VALUES (:system, :cycle, :quarter, :state, :submitDue, "
            + ":reviewDue, :endorseDue, :now, :by, CASE WHEN "
            + windowPassed("CAST(:state AS text)", "CAST(:submitDue AS date)", "CAST(:reviewDue AS date)",
                    "CAST(:endorseDue AS date)")
            + " THEN (SELECT overdue_since FROM not_started_overdue WHERE system_id = :system AND cycle_year = :cycle) "
            + "END) ON CONFLICT (system_id, cycle_year) DO NOTHING RETURNING id";

    /**
     * Flags the Systems among these that have no VA Submission in the Cycle and are not flagged yet, once the Quarter's
     * Submission Window has passed by the Overdue rule, in id order so that two sweeps take the keys in the same order.
     */
    private static final String FLAG_NOT_STARTED =
            "INSERT INTO not_started_overdue (system_id, cycle_year, overdue_since) "
            + "SELECT candidate, :cycle, :now FROM unnest(CAST(:systems AS uuid[])) AS candidate "
            + "WHERE " + windowPassed("'" + NOT_STARTED + "'", "CAST(:submitDue AS date)", "CAST(:reviewDue AS date)",
                    "CAST(:endorseDue AS date)")
            + " AND NOT EXISTS (SELECT 1 FROM va_submission s WHERE s.system_id = candidate AND s.cycle_year = :cycle) "
            + "ORDER BY candidate ON CONFLICT (system_id, cycle_year) DO NOTHING RETURNING system_id";

    /** The last day of the window a Not Started System waits on in a Quarter, by the one rule (V20). */
    private static final String NOT_STARTED_DUE =
            "SELECT current_window_due('" + NOT_STARTED + "', CAST(:submitDue AS date), CAST(:reviewDue AS date), "
            + "CAST(:endorseDue AS date))";

    private static final String NOT_STARTED_OVERDUE =
            "SELECT system_id FROM not_started_overdue WHERE cycle_year = :cycle "
            + "AND system_id = ANY(CAST(:systems AS uuid[]))";

    private static final String INSERT_PARTICIPATION =
            "INSERT INTO submission_participation (submission_id, user_id, step, first_at) "
            + "VALUES (:submission, :user, :step, :now) ON CONFLICT DO NOTHING";

    /**
     * The segregation-of-duties rule, written once: the VA Submissions among these where segregation of duties refuses the
     * person this step, because they acted on it at another step, or because its System is one they are assigned to at
     * another step ({@code :assigned}).
     */
    private static final String CONFLICTED =
            "SELECT s.id FROM va_submission s WHERE s.id = ANY(CAST(:submissions AS uuid[])) "
            + "AND (s.system_id = ANY(CAST(:assigned AS uuid[])) OR EXISTS (SELECT 1 FROM submission_participation p "
            + "WHERE p.submission_id = s.id AND p.user_id = :user AND p.step <> :step))";

    /**
     * A VA Submission's columns, with {@code due_on}: the last day of the window its state waits on, by the one rule
     * (V20); null for a final or frozen state.
     */
    private static final String COLUMNS =
            "SELECT id, system_id, cycle_year, quarter, state, overdue, endorsed_late, submit_due, review_due, "
            + "endorse_due, started_at, submitted_at, overdue_since, "
            + "current_window_due(state, submit_due, review_due, endorse_due) AS due_on FROM va_submission ";

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

    /** A submit moves the VA Submission into the Review Window, and the flag with it ({@link #OVERDUE_AFTER_MOVE}). */
    private static final String SUBMITTED =
            "UPDATE va_submission SET state = :state, submitted_at = :now, submitted_by = :by, " + OVERDUE_AFTER_MOVE
            + " WHERE id = :id";

    /**
     * Flags every VA Submission whose current window has passed by the Overdue rule and that is not flagged yet (ADR
     * 0033); a final or frozen state waits on no window and is never flagged. Only those of the active Systems given are
     * flagged, so a VA Submission of a System no longer in the Register is not (reviewer decisions, 2026-10-23). The rows
     * are claimed in id order and a row another transaction holds is skipped until the next pass (ADR 0012), so this
     * statement never waits on another sweep, an edit or a submit.
     */
    private static final String FLAG_OVERDUE =
            "UPDATE va_submission s SET overdue_since = :now FROM (SELECT id FROM va_submission "
            + "WHERE overdue_since IS NULL AND " + windowPassed("state") + " "
            + "AND system_id = ANY(CAST(:systems AS uuid[])) "
            + "ORDER BY id FOR NO KEY UPDATE SKIP LOCKED) due WHERE s.id = due.id RETURNING s.id, s.system_id, s.state";

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
            LocalDate reviewDue, LocalDate endorseDue, Instant now, LocalDate today, UUID by) {
        return jdbc.sql(INSERT)
                .param("system", systemId)
                .param("cycle", cycleYear)
                .param("quarter", quarter)
                .param("state", state.name())
                .param("submitDue", submitDue)
                .param("reviewDue", reviewDue)
                .param("endorseDue", endorseDue)
                .param("now", utc(now))
                .param("today", today)
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

    /**
     * Moves a VA Submission whose row the caller holds ({@link #lockForSubmit}) to the state, as submitted by this person
     * now, on this business date.
     */
    void submitted(UUID id, SubmissionState state, UUID by, Instant now, LocalDate today) {
        jdbc.sql(SUBMITTED).param("id", id).param("state", state.name()).param("by", by).param("now", utc(now))
                .param("today", today).update();
    }

    /** Flags what is overdue on this business date at this instant, among these active Systems; what was flagged. */
    List<Flagged> flagOverdue(Collection<UUID> activeSystems, LocalDate today, Instant now) {
        if (activeSystems.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FLAG_OVERDUE).param("systems", ids(activeSystems)).param("today", today).param("now", utc(now))
                .query((rs, row) -> new Flagged(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        rs.getString("state")))
                .list();
    }

    /**
     * Flags these Systems of one Quarter Overdue as Not Started in its Cycle, once its Submission Window has passed on
     * this business date, unless started or flagged already; those flagged now.
     */
    List<Flagged> flagNotStarted(Quarter quarter, Collection<UUID> systemIds, LocalDate today, Instant now) {
        if (systemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FLAG_NOT_STARTED)
                .param("cycle", quarter.cycle())
                .param("systems", ids(systemIds))
                .param("submitDue", quarter.submitDue())
                .param("reviewDue", quarter.reviewDue())
                .param("endorseDue", quarter.endorseDue())
                .param("today", today)
                .param("now", utc(now))
                .query((rs, row) -> new Flagged(null, rs.getObject("system_id", UUID.class), NOT_STARTED))
                .list();
    }

    /** The last day of the window a Not Started System of this Quarter waits on. */
    @Nullable LocalDate notStartedDueOn(Quarter quarter) {
        return jdbc.sql(NOT_STARTED_DUE)
                .param("submitDue", quarter.submitDue())
                .param("reviewDue", quarter.reviewDue())
                .param("endorseDue", quarter.endorseDue())
                .query((rs, row) -> rs.getObject(1, LocalDate.class))
                .single();
    }

    /** The Systems among these the sweep flagged Overdue while they were Not Started in the Cycle. */
    Set<UUID> notStartedOverdue(Collection<UUID> systemIds, int cycleYear) {
        if (systemIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(NOT_STARTED_OVERDUE).param("cycle", cycleYear).param("systems", ids(systemIds))
                .query(UUID.class).list());
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
                .param("systems", ids(systemIds))
                .query((rs, row) -> row(rs))
                .list();
    }

    /**
     * The VA Submissions among these where segregation of duties refuses the person this step: they acted on it at another
     * step, or its System is among those they are assigned to at another step.
     */
    Set<UUID> conflicted(Collection<UUID> submissionIds, UUID userId, SubmissionStep step,
            Collection<UUID> assignedElsewhere) {
        if (submissionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(CONFLICTED).param("user", userId).param("step", step.name())
                .param("submissions", ids(submissionIds)).param("assigned", ids(assignedElsewhere))
                .query(UUID.class).list());
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
        return new Row(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class), rs.getInt("cycle_year"),
                rs.getInt("quarter"), SubmissionState.valueOf(rs.getString("state")), rs.getBoolean("overdue"),
                rs.getBoolean("endorsed_late"), rs.getObject("submit_due", LocalDate.class),
                rs.getObject("review_due", LocalDate.class), rs.getObject("endorse_due", LocalDate.class),
                instant(rs, "started_at"), nullableInstant(rs, "submitted_at"), nullableInstant(rs, "overdue_since"),
                rs.getObject("due_on", LocalDate.class));
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
