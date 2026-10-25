package sg.annualva.submission;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.requiredInstant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The Exemption requests and their decisions (V33). Constant SQL, named parameters (as-2). */
@Repository
class ExemptionStore {

    /** A request as the table holds it, with the System of its VA Submission. */
    record Row(
            UUID id, UUID submissionId, UUID systemId, Exemptions.Basis basis, LocalDate vaDate, String evidence,
            @Nullable UUID evidenceFileId, SubmissionState previousState, @Nullable Instant previousOverdueSince,
            UUID requestedBy, Instant requestedAt, Exemptions.@Nullable Decision decision, @Nullable UUID decidedBy,
            @Nullable Instant decidedAt, @Nullable LocalDate lapsesOn, @Nullable LocalDate vaHappenedOn,
            @Nullable String vaEvidence, @Nullable UUID vaRecordedBy, @Nullable Instant vaRecordedAt,
            @Nullable UUID confirmedBy, @Nullable Instant confirmedAt, @Nullable Instant lapsedAt) {

        /**
         * A conditional Exemption (story 47): approved on a scheduled VA (only such a one has a lapse date, V33),
         * with its VA not confirmed and not lapsed.
         */
        boolean conditional() {
            return lapsesOn != null && confirmedAt == null && lapsedAt == null;
        }
    }

    private static final String COLUMNS =
            "SELECT e.id, e.submission_id, s.system_id, e.basis, e.va_date, e.evidence, e.evidence_file_id, "
            + "e.previous_state, e.previous_overdue_since, e.requested_by, e.requested_at, e.decision, e.decided_by, "
            + "e.decided_at, e.lapses_on, e.va_happened_on, e.va_evidence, e.va_recorded_by, e.va_recorded_at, "
            + "e.confirmed_by, e.confirmed_at, e.lapsed_at FROM exemption e JOIN va_submission s ON s.id = e.submission_id ";

    private static final String FIND = COLUMNS + "WHERE e.id = :id";

    /**
     * The pending request of each of these VA Submissions, oldest first: the queue's order. Pending is undecided and its
     * VA Submission still Exemption Requested: a request a Cycle's close made moot (the VA Submission Not Endorsed) stays
     * undecided and is not pending.
     */
    private static final String PENDING =
            COLUMNS + "WHERE e.decision IS NULL AND s.state = 'EXEMPTION_REQUESTED' "
            + "AND e.submission_id = ANY(CAST(:submissions AS uuid[])) ORDER BY e.seq";

    /**
     * The latest Exemption of a VA Submission that was approved on a scheduled VA (it has a lapse date): conditional,
     * confirmed or lapsed.
     */
    private static final String LATEST_CONDITIONAL =
            COLUMNS + "WHERE e.submission_id = :submission AND e.lapses_on IS NOT NULL ORDER BY e.seq DESC LIMIT 1";

    /**
     * The recorded VAs of these VA Submissions that wait on a VA POC's confirmation, the earliest recorded first: recorded,
     * not confirmed, and the VA Submission still Exempted (one a later Register withdraw made final is not waiting).
     */
    private static final String TO_CONFIRM =
            COLUMNS + "WHERE e.va_recorded_at IS NOT NULL AND e.confirmed_at IS NULL "
            + "AND s.state = '" + SubmissionState.EXEMPTED.name() + "' "
            + "AND e.submission_id = ANY(CAST(:submissions AS uuid[])) ORDER BY e.va_recorded_at, e.seq";

    private static final String INSERT =
            "INSERT INTO exemption (submission_id, basis, va_date, evidence, evidence_file_id, previous_state, "
            + "previous_overdue_since, requested_by, requested_at) VALUES (:submission, :basis, :vaDate, :evidence, "
            + "CAST(:file AS uuid), :previous, CAST(:overdueSince AS timestamptz), :by, :now) RETURNING id";

    /** The decision, once: a request already decided is left as it is and nothing is answered. */
    private static final String DECIDE =
            "UPDATE exemption SET decision = :decision, decided_by = :by, decided_at = :now, "
            + "lapses_on = CAST(:lapsesOn AS date) WHERE id = :id AND decision IS NULL";

    /**
     * That the scheduled VA happened, once, while the Exemption is conditional and has not lapsed: a request already
     * recorded, or one with no lapse date, is left as it is and nothing is answered.
     */
    private static final String RECORD_VA =
            "UPDATE exemption SET va_happened_on = :vaDate, va_evidence = :evidence, va_recorded_by = :by, "
            + "va_recorded_at = :now WHERE id = :id AND lapses_on IS NOT NULL AND va_recorded_at IS NULL "
            + "AND lapsed_at IS NULL";

    /** The VA POC's confirmation of a recorded VA, once. */
    private static final String CONFIRM_VA =
            "UPDATE exemption SET confirmed_by = :by, confirmed_at = :now WHERE id = :id "
            + "AND va_recorded_at IS NOT NULL AND confirmed_at IS NULL";

    /**
     * A conditional Exemption past its lapse date with no VA recorded, of an Exempted VA Submission, with what the lapse
     * needs: the state the request froze and the VA Submission's Quarter.
     */
    record Lapsing(UUID exemptionId, UUID submissionId, UUID systemId, int quarter, SubmissionState previousState) {}

    /**
     * The conditional Exemptions of one Cycle that lapse on {@code :today}: approved on a scheduled VA (a lapse date),
     * nothing recorded, not lapsed yet, the lapse date passed, and the VA Submission still Exempted, of the active Systems
     * given. The VA Submissions' rows are claimed in id order and one another transaction holds (a record or a
     * confirmation under way) is skipped until the next pass, as the Overdue sweep does (ADR 0033 item 5).
     */
    private static final String LAPSING =
            "SELECT e.id AS exemption_id, s.id AS submission_id, s.system_id, s.quarter, e.previous_state "
            + "FROM va_submission s JOIN exemption e ON e.submission_id = s.id WHERE s.cycle_year = :cycle "
            + "AND s.state = '" + SubmissionState.EXEMPTED.name() + "' AND s.system_id = ANY(CAST(:systems AS uuid[])) "
            + "AND e.lapses_on < :today AND e.va_recorded_at IS NULL AND e.lapsed_at IS NULL "
            + "ORDER BY s.id FOR NO KEY UPDATE OF s SKIP LOCKED";

    /** The lapse, once, of a conditional Exemption with nothing recorded. */
    private static final String LAPSED =
            "UPDATE exemption SET lapsed_at = :now WHERE id = :id AND va_recorded_at IS NULL AND lapsed_at IS NULL";

    private final JdbcClient jdbc;

    ExemptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps a request of a VA Submission whose row the caller holds; its id. */
    UUID insert(UUID submissionId, ExemptionFields.Parsed fields, SubmissionState previous,
            @Nullable Instant previousOverdueSince, UUID by, Instant now) {
        return jdbc.sql(INSERT)
                .param("submission", submissionId)
                .param("basis", fields.basis().name())
                .param("vaDate", fields.vaDate())
                .param("evidence", fields.evidence())
                .param("file", fields.evidenceFileId())
                .param("previous", previous.name())
                .param("overdueSince", previousOverdueSince == null ? null : utc(previousOverdueSince))
                .param("by", by)
                .param("now", utc(now))
                .query(UUID.class)
                .single();
    }

    /** Records the decision of a pending request; false when it was decided already. */
    boolean decide(UUID id, Exemptions.Decision decision, UUID by, Instant now, @Nullable LocalDate lapsesOn) {
        return jdbc.sql(DECIDE).param("id", id).param("decision", decision.name()).param("by", by)
                .param("now", utc(now)).param("lapsesOn", lapsesOn).update() == 1;
    }

    /** Records that the scheduled VA happened; false when it was recorded already or the Exemption is not conditional. */
    boolean recordVa(UUID id, LocalDate vaDate, String evidence, UUID by, Instant now) {
        return jdbc.sql(RECORD_VA).param("id", id).param("vaDate", vaDate).param("evidence", evidence).param("by", by)
                .param("now", utc(now)).update() == 1;
    }

    /** Confirms the recorded VA; false when nothing is recorded or it was confirmed already. */
    boolean confirmVa(UUID id, UUID by, Instant now) {
        return jdbc.sql(CONFIRM_VA).param("id", id).param("by", by).param("now", utc(now)).update() == 1;
    }

    /** The latest Exemption of the VA Submission approved on a scheduled VA, if it has one. */
    Optional<Row> latestConditional(UUID submissionId) {
        return jdbc.sql(LATEST_CONDITIONAL).param("submission", submissionId).query((rs, row) -> row(rs)).optional();
    }

    /** The conditional Exemptions of one Cycle, of these active Systems, that lapse on this business date. */
    List<Lapsing> lapsing(int cycleYear, Collection<UUID> activeSystems, LocalDate today) {
        if (activeSystems.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(LAPSING)
                .param("cycle", cycleYear)
                .param("systems", activeSystems.stream().map(UUID::toString).toArray(String[]::new))
                .param("today", today)
                .query((rs, row) -> new Lapsing(rs.getObject("exemption_id", UUID.class),
                        rs.getObject("submission_id", UUID.class), rs.getObject("system_id", UUID.class),
                        rs.getInt("quarter"), SubmissionState.valueOf(rs.getString("previous_state"))))
                .list();
    }

    /** Records the lapse of a conditional Exemption whose VA Submission the caller holds; false when it was already. */
    boolean lapsed(UUID id, Instant now) {
        return jdbc.sql(LAPSED).param("id", id).param("now", utc(now)).update() == 1;
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query((rs, row) -> row(rs)).optional();
    }

    /** The pending requests of these VA Submissions, oldest first. */
    List<Row> pending(Collection<UUID> submissionIds) {
        return of(PENDING, submissionIds);
    }

    /** The recorded VAs of these VA Submissions waiting on a confirmation, the earliest recorded first. */
    List<Row> toConfirm(Collection<UUID> submissionIds) {
        return of(TO_CONFIRM, submissionIds);
    }

    private List<Row> of(String sql, Collection<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(sql)
                .param("submissions", submissionIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, row) -> row(rs))
                .list();
    }

    private static Row row(ResultSet rs) throws SQLException {
        String decision = rs.getString("decision");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                rs.getObject("system_id", UUID.class), Exemptions.Basis.valueOf(rs.getString("basis")),
                rs.getObject("va_date", LocalDate.class), rs.getString("evidence"),
                rs.getObject("evidence_file_id", UUID.class), SubmissionState.valueOf(rs.getString("previous_state")),
                instant(rs, "previous_overdue_since"), rs.getObject("requested_by", UUID.class),
                requiredInstant(rs, "requested_at"),
                decision == null ? null : Exemptions.Decision.valueOf(decision), rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"), rs.getObject("lapses_on", LocalDate.class),
                rs.getObject("va_happened_on", LocalDate.class), rs.getString("va_evidence"),
                rs.getObject("va_recorded_by", UUID.class), instant(rs, "va_recorded_at"),
                rs.getObject("confirmed_by", UUID.class), instant(rs, "confirmed_at"), instant(rs, "lapsed_at"));
    }

}
