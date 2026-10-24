package sg.annualva.finding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.parser.Confidence;
import sg.annualva.parser.Severity;

/**
 * The Finding tables. Constant SQL, named parameters (as-2). A Finding is written with its whole file's worth of rows in
 * a handful of statements (the rows go in as arrays), so a report of tens of thousands of Findings is not tens of
 * thousands of round trips.
 */
@Repository
class FindingStore {

    /** A Finding as the table holds it, before its places are added. */
    record Row(UUID id, UUID systemId, ReportType reportType, String identityKey, String identifier,
            String packageOrRule, Severity severity, boolean didNotComplete, @Nullable Confidence confidence,
            int occurrenceCount, @Nullable ReviewState reviewState, @Nullable ProposalView currentProposal,
            FindingSource source, @Nullable String title, @Nullable ReviewView latestReview) {}

    /** Where a Finding is: the VA Submission it belongs to and that VA Submission's System. */
    record Located(UUID submissionId, UUID systemId) {}

    /** The worst severity first, so that a smaller position is a worse one: the order of the enum, written once. */
    private static final String SEVERITY_ORDER = Arrays.stream(Severity.values())
            .map(severity -> "'" + severity.name() + "'").collect(Collectors.joining(", ", "ARRAY[", "]"));

    /**
     * Adds the new Findings, in hash order, each with the severity of its places in the file; one the VA Submission
     * already has (another file of the same Scan Report) is kept as it is and rated from all its places afterwards
     * ({@link #RERATE}), never from a severity it carried, which may be stale once places can be removed (ADR 0029).
     */
    private static final String UPSERT =
            "INSERT INTO finding (submission_id, system_id, report_type, identity_key, identity_hash, identifier, "
            + "package_or_rule, severity, source, created_at) "
            + "SELECT :submission, :system, :type, u.key, u.hash, u.identifier, u.subject, u.severity, 'PARSED', :now "
            + "FROM unnest(CAST(:keys AS text[]), CAST(:hashes AS text[]), CAST(:identifiers AS text[]), "
            + "CAST(:subjects AS text[]), CAST(:severities AS text[])) AS u(key, hash, identifier, subject, severity) "
            + "ORDER BY u.hash ON CONFLICT (submission_id, identity_hash) DO NOTHING";

    /** The most sure first, so that a smaller position is a more sure one: the order of the enum, written once. */
    private static final String CONFIDENCE_ORDER = Arrays.stream(Confidence.values())
            .map(confidence -> "'" + confidence.name() + "'").collect(Collectors.joining(", ", "ARRAY[", "]"));

    /** A place with no confidence is written as the empty text, which is NULL in the table. */
    private static final String ADD_OCCURRENCES =
            "INSERT INTO finding_occurrence (finding_id, report_file_id, locator, severity, did_not_complete, confidence) "
            + "SELECT f.id, :file, u.locator, u.severity, CAST(u.incomplete AS boolean), NULLIF(u.confidence, '') "
            + "FROM unnest(CAST(:hashes AS text[]), CAST(:locators AS text[]), CAST(:severities AS text[]), "
            + "CAST(:incomplete AS text[]), CAST(:confidences AS text[])) "
            + "AS u(hash, locator, severity, incomplete, confidence) "
            + "JOIN finding f ON f.submission_id = :submission AND f.identity_hash = u.hash "
            + "ON CONFLICT (finding_id, report_file_id, locator) DO NOTHING";

    private static final String PROPOSAL_COLUMNS =
            "p.id AS proposal_id, p.status, p.evidence, p.justification, p.reason, p.approver_name, p.approver_role, "
            + "p.approval_memo_file_id, p.plan, p.target_date, p.proposed_at, p.batch_id, p.proposed_as, "
            + "p.replaced_file_id, rp.display_name AS replaced_file_name ";

    /** The file a rescan proposal names as the one it replaced, for its name. */
    private static final String REPLACED_FILE = "LEFT JOIN report_file rp ON rp.id = p.replaced_file_id ";

    /** The Finding's current proposal as {@code p}, for the page, the count and their filters; none when it has none. */
    private static final String CURRENT_PROPOSAL =
            "LEFT JOIN finding_status_proposal p ON p.id = f.current_proposal_id ";

    /**
     * What the list is narrowed to, the same for the page and the count (it reads the Finding, its current proposal,
     * {@link #CURRENT_PROPOSAL}, and its latest review, {@link #LATEST_REVIEW}, only). Each part that is not given matches
     * everything: the Scan Report (no value), the severities (all five are passed), the review state (no value), "needs
     * attention" (false), which is a Finding with no proposal yet or a Disputed one, the batch (no value), whether the
     * current proposal was made in a batch at all (no value), and whether it changed since its last review (no value).
     *
     * <p>A Finding changed since its last review when it has a review and its latest review is not of its current proposal
     * (ticket 32): the SMO/Tech Lead proposed again since, or a rescan left it with none. The latest review is enough,
     * since a review of the current proposal is always later than any review of an earlier one. A Finding never reviewed
     * has not changed since a review; it is awaiting its first one ({@code reviewState=PENDING}).
     */
    private static final String FILTERED =
            "WHERE f.submission_id = :submission "
            + "AND (CAST(:type AS text) IS NULL OR f.report_type = :type) "
            + "AND f.severity = ANY(CAST(:severities AS text[])) "
            + "AND (CAST(:review AS text) IS NULL OR f.review_state = :review) "
            + "AND (NOT :attention OR f.current_proposal_id IS NULL OR f.review_state = 'DISPUTED') "
            + "AND (CAST(:batch AS uuid) IS NULL OR p.batch_id = CAST(:batch AS uuid)) "
            + "AND (CAST(:batched AS boolean) IS NULL OR (p.batch_id IS NOT NULL) = CAST(:batched AS boolean)) "
            + "AND (CAST(:changed AS boolean) IS NULL OR (r.proposal_id IS NOT NULL "
            + "AND r.proposal_id IS DISTINCT FROM f.current_proposal_id) = CAST(:changed AS boolean)) ";

    /**
     * The Finding's latest review, of whichever proposal it was (ticket 28): it says what the Cyber Manager last decided and
     * which proposal that was about, so a reader can tell a review of the current proposal from one of an earlier one.
     */
    private static final String LATEST_REVIEW =
            "LEFT JOIN LATERAL (SELECT * FROM finding_review fr WHERE fr.finding_id = f.id ORDER BY fr.seq DESC LIMIT 1) r "
            + "ON true ";

    /**
     * A Finding did not complete only if it did at every place it was reported (never for a Finding with no place); its
     * confidence is the most sure of its places (none when no place has one).
     */
    private static final String PAGE =
            "SELECT f.id, f.system_id, f.report_type, f.identity_key, f.identifier, f.package_or_rule, f.severity, "
            + "(SELECT COALESCE(bool_and(o.did_not_complete), false) FROM finding_occurrence o WHERE o.finding_id = f.id) "
            + "AS did_not_complete, "
            + "(SELECT o.confidence FROM finding_occurrence o WHERE o.finding_id = f.id AND o.confidence IS NOT NULL "
            + "ORDER BY array_position(" + CONFIDENCE_ORDER + ", o.confidence) LIMIT 1) AS confidence, "
            + "(SELECT count(*) FROM finding_occurrence o WHERE o.finding_id = f.id) AS occurrences, "
            + "f.review_state, f.source, f.title, r.proposal_id AS review_proposal_id, " + ReviewStore.REVIEW_COLUMNS + ", "
            + PROPOSAL_COLUMNS
            + "FROM finding f " + CURRENT_PROPOSAL + REPLACED_FILE
            + LATEST_REVIEW
            + FILTERED
            + "ORDER BY array_position(" + SEVERITY_ORDER + ", f.severity), f.identifier, f.package_or_rule, f.seq "
            + "LIMIT :limit OFFSET :offset";

    private static final String COUNT = "SELECT count(*) FROM finding f " + CURRENT_PROPOSAL + LATEST_REVIEW + FILTERED;

    /** The progress counts of one VA Submission; Carried Over and Target Date Missed have no column before Release 2. */
    private static final String READINESS =
            "SELECT count(*) AS total, count(f.current_proposal_id) AS proposed, "
            + "count(*) FILTER (WHERE f.current_proposal_id IS NULL) AS unproposed, "
            + "count(*) FILTER (WHERE f.review_state = 'PENDING') AS awaiting_review, "
            + "count(*) FILTER (WHERE f.review_state = 'DISPUTED') AS disputed "
            + "FROM finding f WHERE f.submission_id = :submission";

    /** By VA Submission, with its System: the Findings the Cyber Manager confirmed and those awaiting their review. */
    private static final String REVIEW_COUNTS =
            "SELECT f.submission_id, f.system_id, count(*) FILTER (WHERE f.review_state = 'CONFIRMED') AS confirmed, "
            + "count(*) FILTER (WHERE f.review_state = 'PENDING') AS awaiting_review FROM finding f "
            + "WHERE f.submission_id = ANY(CAST(:submissions AS uuid[])) GROUP BY f.submission_id, f.system_id";

    private static final String LOCATE = "SELECT f.submission_id, f.system_id FROM finding f WHERE f.id = :id";

    private static final String BATCH_SUBMISSION = "SELECT b.submission_id FROM bulk_batch b WHERE b.id = :id";

    /**
     * The named Findings that belong to the VA Submission, each row held until the transaction ends. The rows are locked
     * in id order, so two writes over the same Findings (two proposals for one Finding, two batches, or one of each)
     * wait for each other in turn instead of deadlocking, and the last to commit is the current one.
     */
    private static final String LOCK_FINDINGS =
            "SELECT f.id FROM finding f WHERE f.submission_id = :submission AND f.id = ANY(CAST(:ids AS uuid[])) "
            + "ORDER BY f.id FOR UPDATE";

    private static final String INSERT_BATCH =
            "INSERT INTO bulk_batch (submission_id, proposed_by, proposed_at) VALUES (:submission, :by, :now) RETURNING id";

    /**
     * One proposal per Finding, all with the same fields and the same batch (none for a proposal for one Finding). The
     * casts give each NULL its column type.
     */
    private static final String INSERT_PROPOSALS =
            "INSERT INTO finding_status_proposal (finding_id, status, evidence, justification, reason, approver_name, "
            + "approver_role, approval_memo_file_id, plan, target_date, batch_id, proposed_as, replaced_file_id, proposed_by, "
            + "proposed_at) "
            + "SELECT u.id, :status, CAST(:evidence AS text), CAST(:justification AS text), CAST(:reason AS text), "
            + "CAST(:approverName AS text), CAST(:approverRole AS text), CAST(:memo AS uuid), CAST(:plan AS text), "
            + "CAST(:targetDate AS date), CAST(:batch AS uuid), :proposedAs, CAST(:replaced AS uuid), :by, :now "
            + "FROM unnest(CAST(:ids AS uuid[])) WITH ORDINALITY AS u(id, n) ORDER BY u.n "
            + "RETURNING id, finding_id";

    /** Each Finding's latest proposal is now the one just kept, and a new proposal has not been reviewed. */
    private static final String SET_CURRENT =
            "UPDATE finding f SET current_proposal_id = p.id, review_state = 'PENDING' "
            + "FROM finding_status_proposal p WHERE p.id = ANY(CAST(:proposals AS uuid[])) AND p.finding_id = f.id";

    /**
     * Every existing Finding a settlement will write: those of the VA Submission with one of the file's identity keys, and
     * those named as well (a replaced file's). Each row is held until the transaction ends, all locked by this one
     * statement in id order, as every proposal and Bulk-set locks them, so a settlement and a proposal over the same
     * Findings wait for each other instead of deadlocking.
     */
    private static final String LOCK_FOR_SETTLEMENT =
            "SELECT f.id FROM finding f WHERE f.submission_id = :submission "
            + "AND (f.identity_hash = ANY(CAST(:hashes AS text[])) OR f.id = ANY(CAST(:ids AS uuid[]))) "
            + "ORDER BY f.id FOR UPDATE OF f";

    /**
     * Settlements of one VA Submission run one at a time: a lock on the VA Submission held until the transaction ends,
     * taken by settlements only (an advisory lock in a namespace of their own, keyed from the VA Submission's id). So the
     * Findings a settlement locks and rates are counted after every earlier settlement of it has committed, and a key
     * another settlement adds is never written here unlocked and unrated. Proposals do not take it.
     */
    private static final String SERIALISE_SETTLEMENTS =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended("
            + "'finding-settlement:' || CAST(:submission AS text), 0))) l";

    /** The Findings that have a place in this file, in id order. */
    private static final String FINDINGS_OF_FILE =
            "SELECT DISTINCT o.finding_id FROM finding_occurrence o WHERE o.report_file_id = :file ORDER BY o.finding_id";

    /**
     * A Finding a rescan proposed Remediated that a file reports again: the proposal no longer holds, so the Finding has no
     * current proposal and no review (the proposal stays in its history). The ids of those of these keys whose current
     * proposal was a rescan's.
     */
    private static final String WITHDRAW_RESCAN_PROPOSALS =
            "UPDATE finding f SET current_proposal_id = NULL, review_state = NULL FROM finding_status_proposal p "
            + "WHERE p.id = f.current_proposal_id AND p.proposed_as = 'RESCAN' AND f.submission_id = :submission "
            + "AND f.identity_hash = ANY(CAST(:hashes AS text[])) RETURNING f.id";

    private static final String REMOVE_OCCURRENCES = "DELETE FROM finding_occurrence WHERE report_file_id = :file";

    /** A Finding's severity is the highest of its places: for each of these that still has a place, from those it has. */
    private static final String RERATE =
            "UPDATE finding f SET severity = (SELECT o.severity FROM finding_occurrence o WHERE o.finding_id = f.id "
            + "ORDER BY array_position(" + SEVERITY_ORDER + ", o.severity) LIMIT 1) "
            + "WHERE f.id = ANY(CAST(:ids AS uuid[])) "
            + "AND EXISTS (SELECT 1 FROM finding_occurrence o WHERE o.finding_id = f.id)";

    /**
     * Those of these Findings that no file reports any more and whose current proposal is not already Remediated (whoever
     * made it), in id order.
     */
    private static final String UNREPORTED =
            "SELECT f.id FROM finding f WHERE f.id = ANY(CAST(:ids AS uuid[])) "
            + "AND NOT EXISTS (SELECT 1 FROM finding_occurrence o WHERE o.finding_id = f.id) "
            + "AND NOT EXISTS (SELECT 1 FROM finding_status_proposal p WHERE p.id = f.current_proposal_id "
            + "AND p.status = 'REMEDIATED') ORDER BY f.id";

    /** A memo is a supporting evidence file of this VA Submission that has passed every check. */
    private static final String IS_READY_EVIDENCE =
            "SELECT count(*) FROM report_file WHERE id = :file AND submission_id = :submission AND kind = 'EVIDENCE' "
            + "AND pipeline_status = 'READY'";

    /**
     * The Bulk-set batches of a VA Submission in the order they were made, each with the proposal of its first member (every
     * member's has the same status and fields, ADR 0028 item 1) and where its members stand: still in the batch (the
     * current proposal is the batch's) and awaiting review, confirmed or Disputed, or changed since; and how many of those
     * still in it were entered by hand.
     */
    private static final String BATCHES =
            "SELECT b.id AS batch_id_of, b.proposed_by AS batch_by, b.proposed_at AS batch_at, " + PROPOSAL_COLUMNS + ", "
            + "c.members, c.awaiting_review, c.confirmed, c.disputed, c.changed_since, c.manually_entered FROM bulk_batch b "
            + "JOIN LATERAL (SELECT count(*) AS members, "
            + "count(*) FILTER (WHERE f.current_proposal_id = m.id AND f.review_state = 'PENDING') AS awaiting_review, "
            + "count(*) FILTER (WHERE f.current_proposal_id = m.id AND f.review_state = 'CONFIRMED') AS confirmed, "
            + "count(*) FILTER (WHERE f.current_proposal_id = m.id AND f.review_state = 'DISPUTED') AS disputed, "
            + "count(*) FILTER (WHERE f.current_proposal_id IS DISTINCT FROM m.id) AS changed_since, "
            + "count(*) FILTER (WHERE f.current_proposal_id = m.id AND f.source = 'MANUAL') AS manually_entered "
            + "FROM finding_status_proposal m JOIN finding f ON f.id = m.finding_id WHERE m.batch_id = b.id) c ON true "
            + "JOIN LATERAL (SELECT * FROM finding_status_proposal fp WHERE fp.batch_id = b.id ORDER BY fp.seq LIMIT 1) p "
            + "ON true " + REPLACED_FILE
            + "WHERE b.submission_id = :submission ORDER BY b.seq";

    private static final String PROPOSALS =
            "SELECT " + PROPOSAL_COLUMNS + "FROM finding_status_proposal p " + REPLACED_FILE
            + "WHERE p.finding_id = :finding ORDER BY p.seq DESC";

    /** The uploaded files each of these Findings was read from, in the order the files were uploaded. */
    private static final String SOURCES =
            "SELECT o.finding_id, rf.id AS file_id, rf.display_name "
            + "FROM finding_occurrence o JOIN report_file rf ON rf.id = o.report_file_id "
            + "WHERE o.finding_id = ANY(CAST(:ids AS uuid[])) "
            + "GROUP BY o.finding_id, rf.id, rf.display_name, rf.seq ORDER BY o.finding_id, rf.seq";

    private final JdbcClient jdbc;

    FindingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One entry of an array parameter per Finding, in the same order. */
    record Keyed(String key, String hash, String identifier, String subject, Severity severity) {}

    /** One entry per occurrence, keyed by the hash of its Finding's identity key. */
    record Placed(String hash, String locator, Severity severity, boolean didNotComplete, @Nullable Confidence confidence) {}

    /** At most this many rows go in one statement, so the arrays of a statement stay small however large the report. */
    static final int ROWS_PER_STATEMENT = 5_000;

    void upsert(UUID submissionId, UUID systemId, ReportType type, List<Keyed> findings, Instant now) {
        for (int from = 0; from < findings.size(); from += ROWS_PER_STATEMENT) {
            List<Keyed> chunk = findings.subList(from, Math.min(findings.size(), from + ROWS_PER_STATEMENT));
            jdbc.sql(UPSERT)
                    .param("submission", submissionId)
                    .param("system", systemId)
                    .param("type", type.name())
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("keys", chunk.stream().map(Keyed::key).toArray(String[]::new))
                    .param("hashes", chunk.stream().map(Keyed::hash).toArray(String[]::new))
                    .param("identifiers", chunk.stream().map(Keyed::identifier).toArray(String[]::new))
                    .param("subjects", chunk.stream().map(Keyed::subject).toArray(String[]::new))
                    .param("severities", chunk.stream().map(keyed -> keyed.severity().name()).toArray(String[]::new))
                    .update();
        }
    }

    void addOccurrences(UUID submissionId, UUID fileId, List<Placed> places) {
        for (int from = 0; from < places.size(); from += ROWS_PER_STATEMENT) {
            List<Placed> chunk = places.subList(from, Math.min(places.size(), from + ROWS_PER_STATEMENT));
            jdbc.sql(ADD_OCCURRENCES)
                    .param("submission", submissionId)
                    .param("file", fileId)
                    .param("hashes", chunk.stream().map(Placed::hash).toArray(String[]::new))
                    .param("locators", chunk.stream().map(Placed::locator).toArray(String[]::new))
                    .param("severities", chunk.stream().map(place -> place.severity().name()).toArray(String[]::new))
                    .param("incomplete", chunk.stream().map(place -> Boolean.toString(place.didNotComplete()))
                            .toArray(String[]::new))
                    .param("confidences", chunk.stream()
                            .map(place -> place.confidence() == null ? "" : place.confidence().name())
                            .toArray(String[]::new))
                    .update();
        }
    }

    List<Row> page(UUID submissionId, FindingFilter filter, int limit, long offset) {
        return filtered(PAGE, submissionId, filter)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                        ReportType.valueOf(rs.getString("report_type")),
                        rs.getString("identity_key"), rs.getString("identifier"), rs.getString("package_or_rule"),
                        Severity.valueOf(rs.getString("severity")), rs.getBoolean("did_not_complete"),
                        confidenceOf(rs.getString("confidence")), rs.getInt("occurrences"),
                        reviewStateOf(rs.getString("review_state")),
                        rs.getObject("proposal_id") == null ? null : proposal(rs),
                        FindingSource.valueOf(rs.getString("source")), rs.getString("title"),
                        ReviewStore.review(rs, rs.getObject("review_proposal_id", UUID.class), null, null)))
                .list();
    }

    long count(UUID submissionId, FindingFilter filter) {
        return filtered(COUNT, submissionId, filter).query(Long.class).single();
    }

    /** The counts of the VA Submission's Findings, beside the verdict on its report files' age. */
    /** The progress counts of the VA Submission's Findings. */
    Counts counts(UUID submissionId) {
        return jdbc.sql(READINESS).param("submission", submissionId)
                .query((rs, row) -> new Counts(rs.getLong("total"), rs.getLong("proposed"), rs.getLong("unproposed"),
                        rs.getLong("awaiting_review"), rs.getLong("disputed")))
                .single();
    }

    /** How many Findings a VA Submission has, by proposal and review. */
    record Counts(long total, long proposed, long unproposed, long awaitingReview, long disputed) {}

    /** The review counts of one VA Submission, with the System it belongs to. */
    record ReviewTally(UUID submissionId, UUID systemId, ReviewCounts counts) {}

    /** The review counts of these VA Submissions; one with no Finding is absent. */
    List<ReviewTally> reviewCounts(List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(REVIEW_COUNTS).param("submissions", ids(submissionIds))
                .query((rs, row) -> new ReviewTally(rs.getObject("submission_id", UUID.class),
                        rs.getObject("system_id", UUID.class),
                        new ReviewCounts(rs.getLong("confirmed"), rs.getLong("awaiting_review"))))
                .list();
    }

    private JdbcClient.StatementSpec filtered(String sql, UUID submissionId, FindingFilter filter) {
        List<Severity> severities = filter.severities().isEmpty() ? List.of(Severity.values()) : filter.severities();
        return jdbc.sql(sql)
                .param("submission", submissionId)
                .param("type", filter.reportType() == null ? null : filter.reportType().name())
                .param("severities", severities.stream().map(Severity::name).toArray(String[]::new))
                .param("review", filter.reviewState() == null ? null : filter.reviewState().name())
                .param("attention", filter.needsAttention())
                .param("batch", filter.batchId())
                .param("batched", filter.batched())
                .param("changed", filter.changedSinceLastReview());
    }

    private static ProposalView proposal(ResultSet rs) throws SQLException {
        return new ProposalView(rs.getObject("proposal_id", UUID.class), FindingStatus.valueOf(rs.getString("status")),
                rs.getString("evidence"), rs.getString("justification"), rs.getString("reason"),
                rs.getString("approver_name"), rs.getString("approver_role"),
                rs.getObject("approval_memo_file_id", UUID.class), rs.getString("plan"),
                rs.getObject("target_date", LocalDate.class),
                rs.getObject("proposed_at", OffsetDateTime.class).toInstant(), rs.getObject("batch_id", UUID.class),
                ProposedAs.valueOf(rs.getString("proposed_as")), replacedFile(rs));
    }

    private static ProposalView.@Nullable ReplacedFile replacedFile(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("replaced_file_id", UUID.class);
        return id == null ? null : new ProposalView.ReplacedFile(id, rs.getString("replaced_file_name"));
    }

    static @Nullable ReviewState reviewStateOf(@Nullable String name) {
        return name == null ? null : ReviewState.valueOf(name);
    }

    private static @Nullable Confidence confidenceOf(@Nullable String name) {
        return name == null ? null : Confidence.valueOf(name);
    }

    /** Where the Finding is, or nothing for one that does not exist. */
    Optional<Located> locate(UUID findingId) {
        return jdbc.sql(LOCATE).param("id", findingId).query(FindingStore::located).optional();
    }

    /** The VA Submission a Bulk-set batch belongs to, or nothing for one that does not exist. */
    Optional<UUID> batchSubmission(UUID batchId) {
        return jdbc.sql(BATCH_SUBMISSION).param("id", batchId).query(UUID.class).optional();
    }

    /** Locks the named Findings of the VA Submission and answers how many of them there are. */
    int lockFindings(UUID submissionId, List<UUID> findingIds) {
        return jdbc.sql(LOCK_FINDINGS)
                .param("submission", submissionId)
                .param("ids", ids(findingIds))
                .query(UUID.class)
                .list()
                .size();
    }

    private static Located located(ResultSet rs, int row) throws SQLException {
        return new Located(rs.getObject("submission_id", UUID.class), rs.getObject("system_id", UUID.class));
    }

    /** A new Bulk-set batch of the VA Submission. */
    UUID newBatch(UUID submissionId, UUID by, Instant now) {
        return jdbc.sql(INSERT_BATCH)
                .param("submission", submissionId)
                .param("by", by)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    /**
     * Keeps a proposal for each Finding, each its Finding's current one with the review back to PENDING: the one write
     * path for a proposal for one Finding and for a batch. The Findings must be locked first ({@link #lockFindings}).
     *
     * @param batchId the batch the proposals are made in; null for a proposal for one Finding
     * @param proposedAs who the proposals are made as
     * @param replacedFileId for a rescan's proposals, the file the rescan replaced; null otherwise
     * @return the proposals' ids, in the order of the Findings
     */
    List<UUID> propose(List<UUID> findingIds, ProposalFields.Parsed fields, @Nullable UUID batchId,
            ProposedAs proposedAs, @Nullable UUID replacedFileId, UUID by, Instant now) {
        Map<UUID, UUID> byFinding = new HashMap<>();
        jdbc.sql(INSERT_PROPOSALS)
                .param("ids", ids(findingIds))
                .param("status", fields.status().name())
                .param("evidence", fields.evidence())
                .param("justification", fields.justification())
                .param("reason", fields.reason())
                .param("approverName", fields.approverName())
                .param("approverRole", fields.approverRole())
                .param("memo", fields.approvalMemoFileId())
                .param("plan", fields.plan())
                .param("targetDate", fields.targetDate())
                .param("batch", batchId)
                .param("proposedAs", proposedAs.name())
                .param("replaced", replacedFileId)
                .param("by", by)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> Map.entry(rs.getObject("finding_id", UUID.class), rs.getObject("id", UUID.class)))
                .list()
                .forEach(kept -> byFinding.put(kept.getKey(), kept.getValue()));
        List<UUID> proposalIds = findingIds.stream().map(byFinding::get).toList();
        jdbc.sql(SET_CURRENT).param("proposals", ids(proposalIds)).update();
        return proposalIds;
    }

    /**
     * Locks, in one statement and in id order, every existing Finding of the VA Submission with one of these identity
     * hashes or among the ones named.
     *
     * @return the ids locked, in id order
     */
    List<UUID> lockForSettlement(UUID submissionId, List<String> hashes, List<UUID> alsoLock) {
        return jdbc.sql(LOCK_FOR_SETTLEMENT).param("submission", submissionId)
                .param("hashes", hashes.toArray(String[]::new)).param("ids", ids(alsoLock)).query(UUID.class).list();
    }

    /** Waits for the VA Submission's settlement lock and holds it until the transaction ends. */
    void serialiseSettlements(UUID submissionId) {
        jdbc.sql(SERIALISE_SETTLEMENTS).param("submission", submissionId).query(Integer.class).single();
    }

    /** The Findings that have a place in this file, in id order. */
    List<UUID> findingsOf(UUID fileId) {
        return jdbc.sql(FINDINGS_OF_FILE).param("file", fileId).query(UUID.class).list();
    }

    /** Withdraws the current proposal of each of these keys where a rescan made it; those Findings' ids. */
    List<UUID> withdrawRescanProposals(UUID submissionId, List<String> hashes) {
        return jdbc.sql(WITHDRAW_RESCAN_PROPOSALS).param("submission", submissionId)
                .param("hashes", hashes.toArray(String[]::new)).query(UUID.class).list();
    }

    /** Removes every place this file reported. */
    void removeOccurrences(UUID fileId) {
        jdbc.sql(REMOVE_OCCURRENCES).param("file", fileId).update();
    }

    /** Rates each of these Findings that still has a place by the places it has. */
    void rerate(List<UUID> findingIds) {
        jdbc.sql(RERATE).param("ids", ids(findingIds)).update();
    }

    /** Those of these Findings that no file reports any more, in id order. */
    List<UUID> unreported(List<UUID> findingIds) {
        return jdbc.sql(UNREPORTED).param("ids", ids(findingIds)).query(UUID.class).list();
    }

    /** The ids as an array parameter. */
    static String[] ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    /** Whether the file is a supporting evidence file of this VA Submission that has finished its checks. */
    boolean isReadyEvidence(UUID fileId, UUID submissionId) {
        return jdbc.sql(IS_READY_EVIDENCE).param("file", fileId).param("submission", submissionId)
                .query(Long.class).single() > 0;
    }

    /** Every proposal of the Finding, the latest first. */
    List<ProposalView> proposals(UUID findingId) {
        return jdbc.sql(PROPOSALS).param("finding", findingId).query((rs, row) -> proposal(rs)).list();
    }

    /** The Bulk-set batches of the VA Submission, in the order they were made. */
    List<BatchSummary> batches(UUID submissionId) {
        return jdbc.sql(BATCHES).param("submission", submissionId)
                .query((rs, row) -> new BatchSummary(rs.getObject("batch_id_of", UUID.class),
                        rs.getObject("batch_by", UUID.class),
                        rs.getObject("batch_at", OffsetDateTime.class).toInstant(), proposal(rs), rs.getInt("members"),
                        rs.getInt("awaiting_review"), rs.getInt("confirmed"), rs.getInt("disputed"),
                        rs.getInt("changed_since"), rs.getInt("manually_entered")))
                .list();
    }

    /** The files each Finding was read from, by Finding. */
    Map<UUID, List<FindingView.SourceFile>> sources(Collection<UUID> findingIds) {
        Map<UUID, List<FindingView.SourceFile>> byFinding = new HashMap<>();
        if (findingIds.isEmpty()) {
            return byFinding;
        }
        jdbc.sql(SOURCES)
                .param("ids", findingIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, row) -> Map.entry(rs.getObject("finding_id", UUID.class),
                        new FindingView.SourceFile(rs.getObject("file_id", UUID.class), rs.getString("display_name"))))
                .list()
                .forEach(source -> byFinding.computeIfAbsent(source.getKey(), id -> new ArrayList<>())
                        .add(source.getValue()));
        return byFinding;
    }
}
