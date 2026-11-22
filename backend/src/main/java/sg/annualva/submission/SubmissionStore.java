package sg.annualva.submission;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.requiredInstant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.cycle.Quarter;

/**
 * The VA Submission tables and the Declarations accepted at a submit; the wordings are {@link DeclarationStore}'s. Constant
 * SQL, named parameters (as-2).
 */
@Repository
class SubmissionStore {

    /** Where a VA Submission is: its System, its state and its VA Cycle's year. */
    record Located(UUID systemId, SubmissionState state, int cycleYear) {}

    /**
     * What a VA Submission is as the table holds it.
     *
     * @param exemptionLapsed whether a conditional Exemption of it lapsed (ticket 42): a lasting flag, as Endorsed Late is
     */
    record Row(
            UUID id, UUID systemId, int cycleYear, int quarter, SubmissionState state, boolean overdue,
            boolean endorsedLate, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue,
            @Nullable Instant startedAt,
            @Nullable Instant submittedAt, @Nullable Instant overdueSince, @Nullable LocalDate dueOn,
            @Nullable Instant passedToEndorsementAt, @Nullable Instant endorsedAt, boolean exemptionLapsed) {}

    /**
     * What the Overdue sweep has just flagged: a VA Submission, or a System with none (Not Started, no {@code id}), with
     * the state it was in ({@code NOT_STARTED} for a System with none) and the last day of the window that passed (the one
     * rule, V20).
     */
    record Flagged(@Nullable UUID id, UUID systemId, SubmissionState state, @Nullable LocalDate dueOn) {}

    /**
     * What a statement has just moved to a final state (a Cycle's close to Not Endorsed, a Register apply to Withdrawn): a
     * VA Submission, with the state it was in, or, for a close, a System that had none ({@code from} null), now with a Not
     * Endorsed row of its own ({@code id}).
     */
    record Finished(UUID id, UUID systemId, @Nullable SubmissionState from) {}

    /**
     * An in-flight VA Submission (started and not in a final state, {@link SubmissionState#inFlight}) of a System a
     * Register change touches, as the table holds it.
     *
     * @param state as text, so a state no constant names yet can still be read
     */
    record InFlight(UUID id, UUID systemId, int cycleYear, String state) {}

    /** A person who acted at a step of a VA Submission, by their lower-case email (for the Register's preview). */
    record ActedBy(UUID submissionId, SubmissionStep step, String email) {}

    /** How a System with no VA Submission is named to the Overdue rule and in an audit entry. */
    static final String NOT_STARTED = SubmissionState.NOT_STARTED.name();

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

    /** The last day of the window the state waits on, for these windows, by the same rule (V20); null for none. */
    private static String windowDue(String state, String submitDue, String reviewDue, String endorseDue) {
        return "current_window_due(" + state + ", " + submitDue + ", " + reviewDue + ", " + endorseDue + ")";
    }

    /** The last day of the window a Not Started System waits on, for the Quarter's dates given as parameters. */
    private static final String NOT_STARTED_DUE_ON = windowDue("'" + NOT_STARTED + "'", "CAST(:submitDue AS date)",
            "CAST(:reviewDue AS date)", "CAST(:endorseDue AS date)");

    /**
     * What a move to the state {@code :state} does to the flag: it keeps it, and when it was set, only if the window the
     * new state waits on has passed as well, and otherwise clears it (the sweep sets it again when that window passes).
     * Every transition's UPDATE sets this, and nothing else touches {@code overdue_since}. An {@code UPDATE} reads the
     * row's old values, so the new state is the parameter.
     */
    static final String OVERDUE_AFTER_MOVE = overdueAfterMove("overdue_since");

    /**
     * The same rule for a move that puts back the flag's time from before (a rejected Exemption, ADR 0048): {@code since}
     * is kept only if the window the new state waits on has passed.
     */
    private static String overdueAfterMove(String since) {
        return "overdue_since = CASE WHEN " + windowPassed("CAST(:state AS text)") + " THEN " + since + " END";
    }

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
     * A start of a VA Submission that is Not Started (a rejected Exemption request created it, ADR 0048): the move from Not
     * Started, with the windows the row has, and the flag with it ({@link #OVERDUE_AFTER_MOVE}). A row in any other state
     * is left alone and nothing is answered.
     */
    private static final String TAKE_NOT_STARTED =
            "UPDATE va_submission SET state = :state, started_at = :now, started_by = :by, " + OVERDUE_AFTER_MOVE
            + " WHERE system_id = :system AND cycle_year = :cycle AND state = '" + NOT_STARTED + "' RETURNING id";

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
            + "ORDER BY candidate ON CONFLICT (system_id, cycle_year) DO NOTHING RETURNING system_id, "
            + NOT_STARTED_DUE_ON + " AS due_on";

    /** The last day of the window a Not Started System waits on in a Quarter, by the one rule (V20). */
    private static final String NOT_STARTED_DUE = "SELECT " + NOT_STARTED_DUE_ON;

    private static final String NOT_STARTED_OVERDUE =
            "SELECT system_id, overdue_since FROM not_started_overdue WHERE cycle_year = :cycle "
            + "AND system_id = ANY(CAST(:systems AS uuid[]))";

    /**
     * A VA Submission's state, held against any move until the reader's transaction ends: a move's UPDATE waits for it, so
     * whatever the move clears after its commit (a VA POC pair) is written before the move clears it.
     */
    private static final String STATE_HELD = "SELECT state FROM va_submission WHERE id = :id FOR SHARE";

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
            + "endorse_due, started_at, submitted_at, overdue_since, passed_to_endorsement_at, endorsed_at, "
            + windowDue("state", "submit_due", "review_due", "endorse_due") + " AS due_on, "
            + "EXISTS (SELECT 1 FROM exemption e WHERE e.submission_id = va_submission.id AND e.lapsed_at IS NOT NULL) "
            + "AS exemption_lapsed FROM va_submission ";

    private static final String FIND = COLUMNS + "WHERE id = :id";

    private static final String IN_CYCLE =
            COLUMNS + "WHERE cycle_year = :cycle AND system_id = ANY(CAST(:systems AS uuid[]))";

    private static final String LOCATE = "SELECT system_id, state, cycle_year FROM va_submission WHERE id = :id";

    /**
     * A start holds its VA Cycle shared, and a close exclusively, until the transaction ends (ADR 0044): so no VA
     * Submission is started in a Cycle a close is under way in, and a close sees every start that came before it. A
     * transaction-scoped advisory lock in a namespace of its own, keyed by the Cycle's year, because the Cycle may have no
     * row yet when a VA Submission is started before its Q1.
     */
    private static final String LOCK_CYCLE_SHARED =
            "SELECT pg_advisory_xact_lock_shared(hashtext('annualva.cycle'), :cycle)";

    private static final String LOCK_CYCLE = "SELECT pg_advisory_xact_lock(hashtext('annualva.cycle'), :cycle)";

    /**
     * A Cycle's close: every VA Submission of the Cycle in a Quarter the app runs, of the active Systems given, that is
     * in none of the final states, becomes Not Endorsed, and the flag with it ({@link #OVERDUE_AFTER_MOVE}: a final state
     * waits on no window). The rows are held in id order as a move holds them, so the close waits for a command in
     * progress, and a VA Submission that command endorsed meanwhile is read again and left alone.
     */
    private static final String NOT_ENDORSE_UNFINISHED =
            "UPDATE va_submission s SET state = :state, " + OVERDUE_AFTER_MOVE + " FROM (SELECT id, state FROM va_submission "
            + "WHERE cycle_year = :cycle AND quarter >= :firstLive AND state = ANY(CAST(:unfinished AS text[])) "
            + "AND system_id = ANY(CAST(:systems AS uuid[])) ORDER BY id FOR NO KEY UPDATE) due WHERE s.id = due.id "
            + "RETURNING s.id, s.system_id, due.state AS state_from";

    /**
     * A Cycle's close: each of these Systems of one Quarter that has no VA Submission in the Cycle (Not Started) gets one,
     * Not Endorsed, with the Quarter's windows, that nobody started (V30).
     */
    private static final String NOT_ENDORSE_NOT_STARTED =
            "INSERT INTO va_submission (system_id, cycle_year, quarter, state, submit_due, review_due, endorse_due) "
            + "SELECT candidate, :cycle, :quarter, :state, :submitDue, :reviewDue, :endorseDue "
            + "FROM unnest(CAST(:systems AS uuid[])) AS candidate ORDER BY candidate "
            + "ON CONFLICT (system_id, cycle_year) DO NOTHING RETURNING id, system_id";

    /** The in-flight VA Submissions of these Systems, in id order, in any Cycle (ADR 0049). */
    private static final String IN_FLIGHT =
            "SELECT id, system_id, cycle_year, state FROM va_submission WHERE system_id = ANY(CAST(:systems AS uuid[])) "
            + "AND state = ANY(CAST(:inFlight AS text[])) ORDER BY id";

    /** The same, each row held as a move holds it, in id order, until the transaction ends (a Register apply). */
    private static final String HOLD_IN_FLIGHT = IN_FLIGHT + " FOR NO KEY UPDATE";

    /** Who acted at which step of these VA Submissions, by lower-case email. */
    private static final String ACTED_BY =
            "SELECT p.submission_id, p.step, lower(u.email::text) AS email FROM submission_participation p "
            + "JOIN app_user u ON u.id = p.user_id WHERE p.submission_id = ANY(CAST(:submissions AS uuid[]))";

    /**
     * A Register apply's withdraw: each of these VA Submissions still in flight becomes Withdrawn, and the flag with it
     * ({@link #OVERDUE_AFTER_MOVE}: a final state waits on no window). The rows are held in id order as a move holds them.
     */
    private static final String WITHDRAW =
            "UPDATE va_submission s SET state = :state, " + OVERDUE_AFTER_MOVE + " FROM (SELECT id, state FROM va_submission "
            + "WHERE id = ANY(CAST(:submissions AS uuid[])) AND state = ANY(CAST(:inFlight AS text[])) "
            + "ORDER BY id FOR NO KEY UPDATE) due WHERE s.id = due.id RETURNING s.id, s.system_id, due.state AS state_from";

    /**
     * A Not Started System moved to another Quarter: its flag in the Cycle follows the new Quarter's Submission Window by
     * the one rule (V20), so it is cleared while that window has not passed. Only a System still Not Started there: a
     * started one's flag went with its VA Submission, and its row stays as the record.
     */
    private static final String CLEAR_NOT_STARTED =
            "DELETE FROM not_started_overdue WHERE system_id = :system AND cycle_year = :cycle AND NOT "
            + windowPassed("'" + NOT_STARTED + "'", "CAST(:submitDue AS date)", "CAST(:reviewDue AS date)",
                    "CAST(:endorseDue AS date)")
            + " AND NOT EXISTS (SELECT 1 FROM va_submission s WHERE s.system_id = :system AND s.cycle_year = :cycle)";

    /**
     * A write to what the SMO/Tech Lead prepares holds the VA Submission's row shared until it commits, so a submit (which
     * holds it exclusively) waits for the write, and a write that comes after the submit waits for it and then reads the
     * state the submit left.
     */
    private static final String LOCK_SHARED = LOCATE + " FOR SHARE";

    /**
     * A move (a submit, a return, a pass to endorsement) holds the row against every write to what the SMO/Tech Lead
     * prepares and every review until it commits. NO KEY: the rows that refer to the VA Submission (a file, a Finding, a
     * return) can still be inserted meanwhile, by the scan job or by the move itself.
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
            + "ORDER BY id FOR NO KEY UPDATE SKIP LOCKED) due WHERE s.id = due.id RETURNING s.id, s.system_id, s.state, "
            + windowDue("s.state", "s.submit_due", "s.review_due", "s.endorse_due") + " AS due_on";

    /**
     * A move that reaches no milestone (a return for rework): the state, and the flag with it ({@link #OVERDUE_AFTER_MOVE}).
     */
    private static final String MOVED = "UPDATE va_submission SET state = :state, " + OVERDUE_AFTER_MOVE + " WHERE id = :id";

    /**
     * A rejected Exemption (ADR 0048): the state the request froze, and the flag's time from then if the window that
     * state waits on has still passed; otherwise the flag stays clear until the sweep sets it.
     */
    private static final String RESTORED = "UPDATE va_submission SET state = :state, "
            + overdueAfterMove("CAST(:overdueSince AS timestamptz)") + " WHERE id = :id";

    /**
     * A conditional Exemption's lapse while its VA Cycle is open (ticket 42): the state the request froze, with new
     * windows, and the flag with it ({@link #OVERDUE_AFTER_MOVE}; Exempted keeps no flag, so it stays clear until the
     * sweep sets it by the new windows).
     */
    private static final String LAPSED_BACK = "UPDATE va_submission SET state = :state, submit_due = :submitDue, "
            + "review_due = :reviewDue, endorse_due = :endorseDue, " + OVERDUE_AFTER_MOVE + " WHERE id = :id";

    /**
     * A pass to endorsement: Awaiting Endorsement, the flag with it, and when the review ended
     * ({@code passed_to_endorsement_at}, V24, the Cycle matrix's "reviewed").
     */
    private static final String PASSED =
            "UPDATE va_submission SET state = :state, passed_to_endorsement_at = :now, " + OVERDUE_AFTER_MOVE
            + " WHERE id = :id";

    /**
     * An endorsement: Endorsed, the flag with it, when (V24, the Cycle matrix's "endorsed"), and whether it was late:
     * whether the window the old state waited on, the Quarter's last day, had passed by the Overdue rule (V20; an
     * {@code UPDATE} reads the old state).
     */
    private static final String ENDORSED =
            "UPDATE va_submission SET state = :state, endorsed_at = :now, endorsed_late = " + windowPassed("state") + ", "
            + OVERDUE_AFTER_MOVE + " WHERE id = :id RETURNING endorsed_late";

    private static final String INSERT_RETURN =
            "INSERT INTO submission_return (submission_id, step, comment, returned_by, returned_at) "
            + "VALUES (:submission, :step, CAST(:comment AS text), :by, :now) RETURNING id";

    /** The Disputed Findings a return attaches, one row each, in the order given. */
    private static final String INSERT_RETURNED_FINDINGS =
            "INSERT INTO submission_return_item (return_id, finding_id, finding_review_id) SELECT :return, finding, review "
            + "FROM unnest(CAST(:findings AS uuid[]), CAST(:reviews AS uuid[])) WITH ORDINALITY AS item(finding, review, n) "
            + "ORDER BY n";

    /** The Disputed not-applicable justifications a return attaches, one row each, in the order given. */
    private static final String INSERT_RETURNED_REPORTS =
            "INSERT INTO submission_return_item (return_id, report_type, report_review_id) SELECT :return, report_type, "
            + "review FROM unnest(CAST(:types AS text[]), CAST(:reviews AS uuid[])) WITH ORDINALITY "
            + "AS item(report_type, review, n) ORDER BY n";

    private static final String RETURNS =
            "SELECT id, step, comment, returned_by, returned_at FROM submission_return WHERE submission_id = :submission "
            + "ORDER BY seq DESC";

    private static final String RETURN_ITEMS =
            "SELECT i.return_id, i.finding_id, i.finding_review_id, i.report_type, i.report_review_id "
            + "FROM submission_return_item i JOIN submission_return r ON r.id = i.return_id "
            + "WHERE r.submission_id = :submission ORDER BY i.seq";

    /** The VA Submissions, of these Systems, where the person acted at this step. */
    private static final String ACTED_AT =
            "SELECT DISTINCT p.submission_id FROM submission_participation p "
            + "JOIN va_submission s ON s.id = p.submission_id WHERE p.user_id = :user AND p.step = :step "
            + "AND s.system_id = ANY(CAST(:systems AS uuid[]))";

    private static final String PARTICIPATION =
            "SELECT user_id, step, first_at FROM submission_participation WHERE submission_id = :id "
            + "ORDER BY first_at, step, user_id";

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

    /** Starts the Not Started VA Submission of a System in a Cycle, if it has one; its id. */
    Optional<UUID> takeNotStarted(UUID systemId, int cycleYear, Instant now, LocalDate today, UUID by) {
        return jdbc.sql(TAKE_NOT_STARTED)
                .param("system", systemId)
                .param("cycle", cycleYear)
                .param("state", SubmissionState.DRAFT.name())
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

    /**
     * The VA Submission's System and state, its row held against every such write, and every review, until the transaction
     * ends: for a command that moves it to another state.
     */
    Optional<Located> lockForMove(UUID id) {
        return located(LOCK_EXCLUSIVE, id);
    }

    /**
     * Moves a VA Submission whose row the caller holds ({@link #lockForMove}) to the state, as submitted by this person
     * now, on this business date.
     */
    void submitted(UUID id, SubmissionState state, UUID by, Instant now, LocalDate today) {
        jdbc.sql(SUBMITTED).param("id", id).param("state", state.name()).param("by", by).param("now", utc(now))
                .param("today", today).update();
    }

    /** Moves a VA Submission whose row the caller holds ({@link #lockForMove}) to a state with no milestone of its own. */
    void moved(UUID id, SubmissionState state, LocalDate today) {
        jdbc.sql(MOVED).param("id", id).param("state", state.name()).param("today", today).update();
    }

    /**
     * Moves a VA Submission whose row the caller holds ({@link #lockForMove}) out of Exemption Requested: back to the state
     * the request froze, with the Overdue flag's time from then, or to Exempted, which keeps no flag.
     */
    void restored(UUID id, SubmissionState state, @Nullable Instant overdueSince, LocalDate today) {
        jdbc.sql(RESTORED).param("id", id).param("state", state.name())
                .param("overdueSince", overdueSince == null ? null : utc(overdueSince)).param("today", today).update();
    }

    /**
     * Moves a VA Submission whose row the caller holds out of Exempted after its conditional Exemption lapsed: to the
     * state the request froze, with these windows.
     */
    void lapsedBack(UUID id, SubmissionState state, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue,
            LocalDate today) {
        jdbc.sql(LAPSED_BACK).param("id", id).param("state", state.name()).param("submitDue", submitDue)
                .param("reviewDue", reviewDue).param("endorseDue", endorseDue).param("today", today).update();
    }

    /** Passes a VA Submission whose row the caller holds ({@link #lockForMove}) to endorsement now. */
    void passedToEndorsement(UUID id, Instant now, LocalDate today) {
        jdbc.sql(PASSED).param("id", id).param("state", SubmissionState.AWAITING_ENDORSEMENT.name())
                .param("now", utc(now)).param("today", today).update();
    }

    /** Endorses a VA Submission whose row the caller holds ({@link #lockForMove}) now; whether it was Endorsed Late. */
    boolean endorsed(UUID id, Instant now, LocalDate today) {
        return jdbc.sql(ENDORSED).param("id", id).param("state", SubmissionState.ENDORSED.name())
                .param("now", utc(now)).param("today", today).query(Boolean.class).single();
    }

    /** Keeps a return of the VA Submission with what was Disputed when it was returned. */
    void insertReturn(UUID submissionId, SubmissionStep step, @Nullable String comment, UUID by, Instant now,
            ReviewProgress.Disputed disputed) {
        UUID id = jdbc.sql(INSERT_RETURN)
                .param("submission", submissionId)
                .param("step", step.name())
                .param("comment", comment)
                .param("by", by)
                .param("now", utc(now))
                .query(UUID.class)
                .single();
        jdbc.sql(INSERT_RETURNED_FINDINGS)
                .param("return", id)
                .param("findings", ids(disputed.findings().stream().map(ReviewProgress.DisputedFinding::findingId)
                        .toList()))
                .param("reviews", ids(disputed.findings().stream().map(ReviewProgress.DisputedFinding::reviewId)
                        .toList()))
                .update();
        jdbc.sql(INSERT_RETURNED_REPORTS)
                .param("return", id)
                .param("types", disputed.reports().stream().map(ReviewProgress.DisputedReport::reportType)
                        .toArray(String[]::new))
                .param("reviews", ids(disputed.reports().stream().map(ReviewProgress.DisputedReport::reviewId)
                        .toList()))
                .update();
    }

    /**
     * The returns of the VA Submission, the latest first, each with what it attached in the order it was attached. An
     * item is a Finding or a not-applicable justification, never both (V23).
     */
    List<Submissions.Return> returns(UUID submissionId) {
        Map<UUID, List<ReviewProgress.DisputedFinding>> findings = new HashMap<>();
        Map<UUID, List<ReviewProgress.DisputedReport>> reports = new HashMap<>();
        jdbc.sql(RETURN_ITEMS).param("submission", submissionId).query(rs -> {
            UUID returnId = rs.getObject("return_id", UUID.class);
            UUID finding = rs.getObject("finding_id", UUID.class);
            if (finding != null) {
                findings.computeIfAbsent(returnId, key -> new ArrayList<>()).add(
                        new ReviewProgress.DisputedFinding(finding, rs.getObject("finding_review_id", UUID.class)));
            } else {
                reports.computeIfAbsent(returnId, key -> new ArrayList<>()).add(new ReviewProgress.DisputedReport(
                        rs.getString("report_type"), rs.getObject("report_review_id", UUID.class)));
            }
        });
        return jdbc.sql(RETURNS).param("submission", submissionId).query((rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new Submissions.Return(id, SubmissionStep.valueOf(rs.getString("step")), rs.getString("comment"),
                    rs.getObject("returned_by", UUID.class), requiredInstant(rs, "returned_at"),
                    findings.getOrDefault(id, List.of()), reports.getOrDefault(id, List.of()));
        }).list();
    }

    /** Flags what is overdue on this business date at this instant, among these active Systems; what was flagged. */
    List<Flagged> flagOverdue(Collection<UUID> activeSystems, LocalDate today, Instant now) {
        if (activeSystems.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FLAG_OVERDUE).param("systems", ids(activeSystems)).param("today", today).param("now", utc(now))
                .query((rs, row) -> new Flagged(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        SubmissionState.valueOf(rs.getString("state")), rs.getObject("due_on", LocalDate.class)))
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
                .query((rs, row) -> new Flagged(null, rs.getObject("system_id", UUID.class), SubmissionState.NOT_STARTED,
                        rs.getObject("due_on", LocalDate.class)))
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
        return notStartedOverdueSince(systemIds, cycleYear).keySet();
    }

    /** The same, each with when the sweep flagged it. */
    Map<UUID, Instant> notStartedOverdueSince(Collection<UUID> systemIds, int cycleYear) {
        Map<UUID, Instant> flagged = new HashMap<>();
        if (!systemIds.isEmpty()) {
            jdbc.sql(NOT_STARTED_OVERDUE).param("cycle", cycleYear).param("systems", ids(systemIds))
                    .query(rs -> {
                        flagged.put(rs.getObject("system_id", UUID.class), requiredInstant(rs, "overdue_since"));
                    });
        }
        return flagged;
    }

    /** The VA Submission's state, held until the caller's transaction ends ({@link #STATE_HELD}). */
    Optional<SubmissionState> stateHeld(UUID id) {
        return jdbc.sql(STATE_HELD).param("id", id).query((rs, row) -> SubmissionState.valueOf(rs.getString("state")))
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

    /** The VA Submissions of these Systems where the person acted at the step. */
    Set<UUID> actedAt(UUID userId, SubmissionStep step, Collection<UUID> systemIds) {
        if (systemIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(ACTED_AT).param("user", userId).param("step", step.name())
                .param("systems", ids(systemIds)).query(UUID.class).list());
    }

    List<Submissions.Acted> participation(UUID submissionId) {
        return jdbc.sql(PARTICIPATION).param("id", submissionId).query((rs, row) -> new Submissions.Acted(
                SubmissionStep.valueOf(rs.getString("step")), rs.getObject("user_id", UUID.class),
                requiredInstant(rs, "first_at"))).list();
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
                        rs.getObject("accepted_by", UUID.class), requiredInstant(rs, "accepted_at")))
                .optional();
    }

    /** Holds the VA Cycle against a close until the transaction ends, for a start. */
    void holdCycle(int cycleYear) {
        jdbc.sql(LOCK_CYCLE_SHARED).param("cycle", cycleYear).query().singleRow();
    }

    /**
     * Holds the VA Cycle against every start, every close and every Register apply until the transaction ends, for a close
     * or an apply. The locks are always taken in the order Register apply lock, Cycle lock, VA Submission rows (ADR 0044).
     */
    void lockCycle(int cycleYear) {
        jdbc.sql(LOCK_CYCLE).param("cycle", cycleYear).query().singleRow();
    }

    /** The in-flight VA Submissions of these Systems. */
    List<InFlight> inFlight(Collection<UUID> systemIds) {
        return inFlight(IN_FLIGHT, systemIds);
    }

    /** The same, each held as a move holds it until the transaction ends. */
    List<InFlight> holdInFlight(Collection<UUID> systemIds) {
        return inFlight(HOLD_IN_FLIGHT, systemIds);
    }

    private List<InFlight> inFlight(String sql, Collection<UUID> systemIds) {
        if (systemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(sql).param("systems", ids(systemIds))
                .param("inFlight", inFlightStates())
                .query((rs, row) -> new InFlight(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        rs.getInt("cycle_year"), rs.getString("state")))
                .list();
    }

    /** Who acted at which step of these VA Submissions. */
    List<ActedBy> actedBy(Collection<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ACTED_BY).param("submissions", ids(submissionIds))
                .query((rs, row) -> new ActedBy(rs.getObject("submission_id", UUID.class),
                        SubmissionStep.valueOf(rs.getString("step")), rs.getString("email")))
                .list();
    }

    /** Withdraws these VA Submissions that are still in flight on this business date; those it moved. */
    List<Finished> withdraw(Collection<UUID> submissionIds, LocalDate today) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(WITHDRAW)
                .param("state", SubmissionState.WITHDRAWN.name())
                .param("submissions", ids(submissionIds))
                .param("inFlight", inFlightStates())
                .param("today", today)
                .query((rs, row) -> new Finished(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        SubmissionState.valueOf(rs.getString("state_from"))))
                .list();
    }

    /** Clears a moved Not Started System's flag in the Quarter's Cycle when its Submission Window has not passed. */
    void clearNotStartedOverdue(UUID systemId, Quarter quarter, LocalDate today) {
        jdbc.sql(CLEAR_NOT_STARTED)
                .param("system", systemId)
                .param("cycle", quarter.cycle())
                .param("submitDue", quarter.submitDue())
                .param("reviewDue", quarter.reviewDue())
                .param("endorseDue", quarter.endorseDue())
                .param("today", today)
                .update();
    }

    /** The states that are not final (ADR 0044): what a close makes Not Endorsed. */
    private static String[] unfinishedStates() {
        return Arrays.stream(SubmissionState.values()).filter(state -> !state.isFinal()).map(SubmissionState::name)
                .toArray(String[]::new);
    }

    /** The states of in-flight work (ADR 0049): what a Register change touches and a withdraw makes Withdrawn. */
    private static String[] inFlightStates() {
        return Arrays.stream(SubmissionState.values()).filter(SubmissionState::inFlight).map(SubmissionState::name)
                .toArray(String[]::new);
    }

    /**
     * Makes Not Endorsed every VA Submission of the Cycle, from its first live Quarter, of these active Systems, that is not
     * in a final state; those it moved, with the state each was in.
     */
    List<Finished> notEndorseUnfinished(int cycleYear, int firstLiveQuarter, Collection<UUID> activeSystems,
            LocalDate today) {
        if (activeSystems.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(NOT_ENDORSE_UNFINISHED)
                .param("state", SubmissionState.NOT_ENDORSED.name())
                .param("cycle", cycleYear)
                .param("firstLive", firstLiveQuarter)
                .param("unfinished", unfinishedStates())
                .param("systems", ids(activeSystems))
                .param("today", today)
                .query((rs, row) -> new Finished(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        SubmissionState.valueOf(rs.getString("state_from"))))
                .list();
    }

    /** Gives each of these Systems of the Quarter with no VA Submission in its Cycle a Not Endorsed one; those it made. */
    List<Finished> notEndorseNotStarted(Quarter quarter, Collection<UUID> systemIds) {
        if (systemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(NOT_ENDORSE_NOT_STARTED)
                .param("cycle", quarter.cycle())
                .param("quarter", quarter.number())
                .param("state", SubmissionState.NOT_ENDORSED.name())
                .param("submitDue", quarter.submitDue())
                .param("reviewDue", quarter.reviewDue())
                .param("endorseDue", quarter.endorseDue())
                .param("systems", ids(systemIds))
                .query((rs, row) -> new Finished(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        null))
                .list();
    }

    private Optional<Located> located(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id)
                .query((rs, row) -> new Located(rs.getObject("system_id", UUID.class),
                        SubmissionState.valueOf(rs.getString("state")),
                        rs.getInt("cycle_year")))
                .optional();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class), rs.getInt("cycle_year"),
                rs.getInt("quarter"), SubmissionState.valueOf(rs.getString("state")), rs.getBoolean("overdue"),
                rs.getBoolean("endorsed_late"), rs.getObject("submit_due", LocalDate.class),
                rs.getObject("review_due", LocalDate.class), rs.getObject("endorse_due", LocalDate.class),
                instant(rs, "started_at"), instant(rs, "submitted_at"), instant(rs, "overdue_since"),
                rs.getObject("due_on", LocalDate.class), instant(rs, "passed_to_endorsement_at"),
                instant(rs, "endorsed_at"), rs.getBoolean("exemption_lapsed"));
    }



    private static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

}
