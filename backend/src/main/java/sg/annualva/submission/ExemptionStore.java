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

/** The Exemption requests and their decisions (V144_40). Constant SQL, named parameters (as-2). */
@Repository
class ExemptionStore {

    /** A request as the table holds it, with the System of its VA Submission. */
    record Row(
            UUID id, UUID submissionId, UUID systemId, Exemptions.Basis basis, LocalDate vaDate, String evidence,
            @Nullable UUID evidenceFileId, SubmissionState previousState, @Nullable Instant previousOverdueSince,
            UUID requestedBy, Instant requestedAt, Exemptions.@Nullable Decision decision, @Nullable UUID decidedBy,
            @Nullable Instant decidedAt, @Nullable LocalDate lapsesOn) {}

    private static final String COLUMNS =
            "SELECT e.id, e.submission_id, s.system_id, e.basis, e.va_date, e.evidence, e.evidence_file_id, "
            + "e.previous_state, e.previous_overdue_since, e.requested_by, e.requested_at, e.decision, e.decided_by, "
            + "e.decided_at, e.lapses_on FROM exemption e JOIN va_submission s ON s.id = e.submission_id ";

    private static final String FIND = COLUMNS + "WHERE e.id = :id";

    /**
     * The pending request of each of these VA Submissions, oldest first: the queue's order. Pending is undecided and its
     * VA Submission still Exemption Requested: a request a Cycle's close made moot (the VA Submission Not Endorsed) stays
     * undecided and is not pending.
     */
    private static final String PENDING =
            COLUMNS + "WHERE e.decision IS NULL AND s.state = 'EXEMPTION_REQUESTED' "
            + "AND e.submission_id = ANY(CAST(:submissions AS uuid[])) ORDER BY e.seq";

    private static final String INSERT =
            "INSERT INTO exemption (submission_id, basis, va_date, evidence, evidence_file_id, previous_state, "
            + "previous_overdue_since, requested_by, requested_at) VALUES (:submission, :basis, :vaDate, :evidence, "
            + "CAST(:file AS uuid), :previous, CAST(:overdueSince AS timestamptz), :by, :now) RETURNING id";

    /** The decision, once: a request already decided is left as it is and nothing is answered. */
    private static final String DECIDE =
            "UPDATE exemption SET decision = :decision, decided_by = :by, decided_at = :now, "
            + "lapses_on = CAST(:lapsesOn AS date) WHERE id = :id AND decision IS NULL";

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

    Optional<Row> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query((rs, row) -> row(rs)).optional();
    }

    /** The pending requests of these VA Submissions, oldest first. */
    List<Row> pending(Collection<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(PENDING)
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
                rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                decision == null ? null : Exemptions.Decision.valueOf(decision), rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"), rs.getObject("lapses_on", LocalDate.class));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
