package sg.annualva.finding;

import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
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
 * The reviews of Findings' proposals and of not-applicable justifications (ticket 28), one at a time or a Bulk-set batch at
 * once (ticket 29). Constant SQL, named parameters (as-2). Every write takes a {@link Submissions.ReviewTarget}, so no
 * review is written without the VA Submission held In Review and segregation of duties checked.
 */
@Repository
class ReviewStore {

    /** A Finding of the VA Submission, its row held: its current proposal, or null when it has none. */
    record LockedFinding(@Nullable UUID currentProposal) {}

    /**
     * A member of a Bulk-set batch, its Finding's row held.
     *
     * @param batchProposal the proposal the batch made for it
     * @param currentProposal its current proposal; the batch's only while nothing changed it since
     * @param reviewState the review of its current proposal; null when it has none
     */
    record LockedMember(UUID findingId, UUID batchProposal, @Nullable UUID currentProposal,
            @Nullable ReviewState reviewState) {}

    /** A Finding and the proposal of it that a review decides. */
    record Reviewed(UUID findingId, UUID proposalId) {}

    /** A Scan Report of the VA Submission, its row held: its mode and the version of its justification. */
    record LockedSlot(ReportMode mode, int justificationVersion) {}

    /** The columns of a review, as {@link #review} reads them; a Finding's has no justification. */
    static final String REVIEW_COLUMNS = "r.id AS review_id, r.decision AS review_decision, r.comment AS review_comment, "
            + "r.reviewed_by AS review_by, r.reviewed_at AS review_at ";

    /** The Finding's row, held until the transaction ends, as a proposal holds it (ADR 0027 item 2). */
    private static final String LOCK_FINDING =
            "SELECT current_proposal_id FROM finding WHERE id = :id AND submission_id = :submission FOR UPDATE";

    /**
     * The members of a Bulk-set batch, each Finding's row held until the transaction ends. The rows are locked in id order,
     * as a proposal and a Bulk-set lock them (ADR 0028 item 4), so two reviews over the same Findings wait for each other
     * instead of deadlocking.
     */
    private static final String LOCK_BATCH =
            "SELECT f.id, m.id AS batch_proposal, f.current_proposal_id, f.review_state FROM finding_status_proposal m "
            + "JOIN finding f ON f.id = m.finding_id WHERE m.batch_id = :batch AND f.submission_id = :submission "
            + "ORDER BY f.id FOR UPDATE OF f";

    private static final String INSERT_BATCH_REVIEW =
            "INSERT INTO batch_review (batch_id, reviewed_by, reviewed_at) VALUES (:batch, :by, :now) RETURNING id";

    /**
     * One review per Finding, all with the same decision and comment, and the same batch review (none for a review of one
     * Finding). The casts give each NULL its column type.
     */
    private static final String INSERT_FINDING_REVIEWS =
            "INSERT INTO finding_review (finding_id, proposal_id, decision, comment, batch_review_id, reviewed_by, "
            + "reviewed_at) SELECT u.finding, u.proposal, :decision, CAST(:comment AS text), CAST(:batchReview AS uuid), "
            + ":by, :now FROM unnest(CAST(:findings AS uuid[]), CAST(:proposals AS uuid[])) WITH ORDINALITY "
            + "AS u(finding, proposal, n) ORDER BY u.n RETURNING id, finding_id";

    /**
     * The Findings take the decision. {@link Reviews} has checked, with each Finding held, that the proposal reviewed is its
     * current one: that check owns the rule, and a new proposal puts the state back to PENDING (V14).
     */
    private static final String SET_FINDING_STATES =
            "UPDATE finding SET review_state = :decision WHERE id = ANY(CAST(:findings AS uuid[]))";

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

    /** Holds every member of the batch, in id order; none when the batch is not of this VA Submission. */
    List<LockedMember> lockBatch(Submissions.ReviewTarget target, UUID batchId) {
        return jdbc.sql(LOCK_BATCH).param("batch", batchId).param("submission", target.submissionId())
                .query((rs, row) -> new LockedMember(rs.getObject("id", UUID.class),
                        rs.getObject("batch_proposal", UUID.class), rs.getObject("current_proposal_id", UUID.class),
                        FindingStore.reviewStateOf(rs.getString("review_state"))))
                .list();
    }

    /** A new review of the batch: who and when; what it decided is on the reviews of its Findings. */
    UUID newBatchReview(Submissions.ReviewTarget target, UUID batchId, UUID by, Instant now) {
        return jdbc.sql(INSERT_BATCH_REVIEW).param("batch", batchId).param("by", by)
                .param("now", utc(now)).query(UUID.class).single();
    }

    /**
     * Keeps a review of each Finding's current proposal, held by {@link #lockFinding} or {@link #lockBatch}, and gives each
     * Finding its state: the one write path for a review of one Finding and of a batch.
     *
     * @param batchReview the batch review they are made in; null for a review of one Finding
     * @return the reviews, in the order of the Findings
     */
    List<ReviewView> reviewFindings(Submissions.ReviewTarget target, List<Reviewed> reviewed,
            ReviewFields.Parsed fields, @Nullable UUID batchReview, UUID by, Instant now) {
        Map<UUID, UUID> byFinding = new HashMap<>();
        jdbc.sql(INSERT_FINDING_REVIEWS)
                .param("findings", FindingStore.ids(reviewed.stream().map(Reviewed::findingId).toList()))
                .param("proposals", FindingStore.ids(reviewed.stream().map(Reviewed::proposalId).toList()))
                .param("decision", fields.decision().name())
                .param("comment", fields.comment())
                .param("batchReview", batchReview)
                .param("by", by)
                .param("now", utc(now))
                .query((rs, row) -> Map.entry(rs.getObject("finding_id", UUID.class), rs.getObject("id", UUID.class)))
                .list()
                .forEach(kept -> byFinding.put(kept.getKey(), kept.getValue()));
        jdbc.sql(SET_FINDING_STATES).param("decision", fields.decision().name())
                .param("findings", FindingStore.ids(reviewed.stream().map(Reviewed::findingId).toList())).update();
        return reviewed.stream()
                .map(each -> new ReviewView(byFinding.get(each.findingId()), fields.decision(), fields.comment(),
                        each.proposalId(), null, null, by, now))
                .toList();
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
                .param("now", utc(now))
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
