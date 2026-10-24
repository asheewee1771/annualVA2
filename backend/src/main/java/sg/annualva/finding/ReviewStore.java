package sg.annualva.finding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.submission.ReviewProgress;
import sg.annualva.submission.Submissions;

/**
 * The reviews of Findings' proposals and of not-applicable justifications (ticket 28). Constant SQL, named parameters
 * (as-2). Every write takes a {@link Submissions.ReviewTarget}, so no review is written without the VA Submission held In
 * Review and segregation of duties checked.
 */
@Repository
class ReviewStore {

    /** A Finding of the VA Submission, its row held: its current proposal, or null when it has none. */
    record LockedFinding(@Nullable UUID currentProposal) {}

    /** A Scan Report of the VA Submission, its row held: its mode and the version of its justification. */
    record LockedSlot(ReportMode mode, int justificationVersion) {}

    /** The columns of a review, as {@link #review} reads them; a Finding's has no justification. */
    static final String REVIEW_COLUMNS = "r.id AS review_id, r.decision AS review_decision, r.comment AS review_comment, "
            + "r.reviewed_by AS review_by, r.reviewed_at AS review_at ";

    /** The Finding's row, held until the transaction ends, as a proposal holds it (ADR 0027 item 2). */
    private static final String LOCK_FINDING =
            "SELECT current_proposal_id FROM finding WHERE id = :id AND submission_id = :submission FOR UPDATE";

    private static final String INSERT_FINDING_REVIEW =
            "INSERT INTO finding_review (finding_id, proposal_id, decision, comment, reviewed_by, reviewed_at) "
            + "VALUES (:finding, :proposal, :decision, CAST(:comment AS text), :by, :now) RETURNING id";

    /**
     * The Finding takes the decision. {@link Reviews} has checked, with the Finding held, that the proposal reviewed is its
     * current one: that check owns the rule, and a new proposal puts the state back to PENDING (V14).
     */
    private static final String SET_FINDING_STATE =
            "UPDATE finding SET review_state = :decision WHERE id = :finding";

    /** The Scan Report's row, held until the transaction ends, as a mode change holds it (ADR 0030 item 6). */
    private static final String LOCK_SLOT =
            "SELECT mode, justification_version FROM report_slot WHERE submission_id = :submission "
            + "AND report_type = :type FOR UPDATE";

    /** The justification reviewed is copied from the Scan Report by the statement that keeps the review. */
    private static final String INSERT_REPORT_REVIEW =
            "INSERT INTO report_review (submission_id, report_type, decision, comment, justification, "
            + "justification_version, reviewed_by, reviewed_at) SELECT submission_id, report_type, :decision, "
            + "CAST(:comment AS text), justification, justification_version, :by, :now "
            + "FROM report_slot WHERE submission_id = :submission AND report_type = :type "
            + "RETURNING id, justification, justification_version";

    private static final String SET_SLOT_STATE =
            "UPDATE report_slot SET review_state = :decision WHERE submission_id = :submission AND report_type = :type";

    /** The latest review of each Scan Report of the VA Submission that has one. */
    private static final String LATEST_OF_REPORTS =
            "SELECT DISTINCT ON (r.report_type) r.report_type, r.justification, r.justification_version, "
            + REVIEW_COLUMNS
            + "FROM report_review r WHERE r.submission_id = :submission ORDER BY r.report_type, r.seq DESC";

    /**
     * The Disputed Findings of a VA Submission in the order they were read, each with its latest review: the one that
     * Disputed its current proposal, since a review sets the state and a new proposal puts it back to PENDING.
     */
    private static final String DISPUTED_FINDINGS =
            "SELECT f.id, r.id AS review_id FROM finding f JOIN LATERAL (SELECT id FROM finding_review "
            + "WHERE finding_id = f.id ORDER BY seq DESC LIMIT 1) r ON true "
            + "WHERE f.submission_id = :submission AND f.review_state = 'DISPUTED' ORDER BY f.seq";

    /** The Disputed not-applicable justifications of a VA Submission, each with its latest review, the one that Disputed it. */
    private static final String DISPUTED_JUSTIFICATIONS =
            "SELECT s.report_type, r.id AS review_id FROM report_slot s JOIN LATERAL (SELECT id FROM report_review "
            + "WHERE submission_id = s.submission_id AND report_type = s.report_type ORDER BY seq DESC LIMIT 1) r ON true "
            + "WHERE s.submission_id = :submission AND s.mode = 'NOT_APPLICABLE' AND s.review_state = 'DISPUTED'";

    private final JdbcClient jdbc;

    ReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Holds a Finding of the VA Submission; nothing when it is not one of its Findings. */
    Optional<LockedFinding> lockFinding(Submissions.ReviewTarget target, UUID findingId) {
        return jdbc.sql(LOCK_FINDING).param("id", findingId).param("submission", target.submissionId())
                .query((rs, row) -> new LockedFinding(rs.getObject("current_proposal_id", UUID.class)))
                .optional();
    }

    /** Keeps a review of the Finding's current proposal, held by {@link #lockFinding}, and gives the Finding its state. */
    ReviewView reviewFinding(Submissions.ReviewTarget target, UUID findingId, ReviewFields.Parsed fields, UUID by,
            Instant now) {
        UUID proposal = fields.proposalId();
        UUID id = jdbc.sql(INSERT_FINDING_REVIEW)
                .param("finding", findingId)
                .param("proposal", proposal)
                .param("decision", fields.decision().name())
                .param("comment", fields.comment())
                .param("by", by)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .single();
        jdbc.sql(SET_FINDING_STATE).param("decision", fields.decision().name()).param("finding", findingId).update();
        return new ReviewView(id, fields.decision(), fields.comment(), proposal, null, null, by, now);
    }

    /** The mode of a Scan Report that has a row, held until the transaction ends; nothing for one never written (uploaded). */
    Optional<LockedSlot> lockSlot(Submissions.ReviewTarget target, ReportType type) {
        return jdbc.sql(LOCK_SLOT).param("submission", target.submissionId()).param("type", type.name())
                .query((rs, row) -> new LockedSlot(ReportMode.valueOf(rs.getString("mode")),
                        rs.getInt("justification_version")))
                .optional();
    }

    /** Keeps a review of the not-applicable justification, held by {@link #lockSlot}, and gives it its state. */
    ReviewView reviewReport(Submissions.ReviewTarget target, ReportType type, ReviewFields.Parsed fields, UUID by,
            Instant now) {
        ReviewView kept = jdbc.sql(INSERT_REPORT_REVIEW)
                .param("submission", target.submissionId())
                .param("type", type.name())
                .param("decision", fields.decision().name())
                .param("comment", fields.comment())
                .param("by", by)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> new ReviewView(rs.getObject("id", UUID.class), fields.decision(), fields.comment(),
                        null, rs.getString("justification"), rs.getInt("justification_version"), by, now))
                .single();
        jdbc.sql(SET_SLOT_STATE).param("decision", fields.decision().name())
                .param("submission", target.submissionId()).param("type", type.name()).update();
        return kept;
    }

    /** The latest review of each Scan Report of the VA Submission, by Scan Report; one never reviewed is absent. */
    Map<ReportType, ReviewView> latestOfReports(UUID submissionId) {
        Map<ReportType, ReviewView> latest = new EnumMap<>(ReportType.class);
        jdbc.sql(LATEST_OF_REPORTS).param("submission", submissionId)
                .query((rs, row) -> Map.entry(ReportType.valueOf(rs.getString("report_type")),
                        review(rs, null, rs.getString("justification"), rs.getInt("justification_version"))))
                .list()
                .forEach(entry -> latest.put(entry.getKey(), entry.getValue()));
        return latest;
    }

    /** The Disputed Findings of the VA Submission in the order they were read, each with the review that Disputed it. */
    List<ReviewProgress.DisputedFinding> disputedFindings(UUID submissionId) {
        return jdbc.sql(DISPUTED_FINDINGS).param("submission", submissionId)
                .query((rs, row) -> new ReviewProgress.DisputedFinding(rs.getObject("id", UUID.class),
                        rs.getObject("review_id", UUID.class)))
                .list();
    }

    /** The Disputed not-applicable justifications of the VA Submission in Scan Report order, each with its review. */
    List<ReviewProgress.DisputedReport> disputedReports(UUID submissionId) {
        return jdbc.sql(DISPUTED_JUSTIFICATIONS).param("submission", submissionId)
                .query((rs, row) -> Map.entry(ReportType.valueOf(rs.getString("report_type")),
                        rs.getObject("review_id", UUID.class)))
                .list().stream().sorted(Map.Entry.comparingByKey())
                .map(each -> new ReviewProgress.DisputedReport(each.getKey().name(), each.getValue()))
                .toList();
    }

    /** A review read through {@link #REVIEW_COLUMNS}; null when the row has none (an outer join that matched nothing). */
    static @Nullable ReviewView review(ResultSet rs, @Nullable UUID proposalId, @Nullable String justification,
            @Nullable Integer justificationVersion)
            throws SQLException {
        UUID id = rs.getObject("review_id", UUID.class);
        if (id == null) {
            return null;
        }
        return new ReviewView(id, ReviewDecision.valueOf(rs.getString("review_decision")),
                rs.getString("review_comment"), proposalId, justification, justificationVersion,
                rs.getObject("review_by", UUID.class),
                rs.getObject("review_at", OffsetDateTime.class).toInstant());
    }
}
