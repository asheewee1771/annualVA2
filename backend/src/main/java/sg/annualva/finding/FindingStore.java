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
            int occurrenceCount, @Nullable ReviewState reviewState, @Nullable ProposalView currentProposal) {}

    /** Where a Finding is: the VA Submission it belongs to and that VA Submission's System. */
    record Located(UUID submissionId, UUID systemId) {}

    /** The worst severity first, so that a smaller position is a worse one: the order of the enum, written once. */
    private static final String SEVERITY_ORDER = Arrays.stream(Severity.values())
            .map(severity -> "'" + severity.name() + "'").collect(Collectors.joining(", ", "ARRAY[", "]"));

    /**
     * Adds the Findings, or, when the VA Submission already has one with that identity (another file of the same Scan
     * Report), keeps it and raises its severity to the worse of the two.
     */
    private static final String UPSERT =
            "INSERT INTO finding (submission_id, system_id, report_type, identity_key, identity_hash, identifier, "
            + "package_or_rule, severity, created_at) "
            + "SELECT :submission, :system, :type, u.key, u.hash, u.identifier, u.subject, u.severity, :now "
            + "FROM unnest(CAST(:keys AS text[]), CAST(:hashes AS text[]), CAST(:identifiers AS text[]), "
            + "CAST(:subjects AS text[]), CAST(:severities AS text[])) AS u(key, hash, identifier, subject, severity) "
            + "ON CONFLICT (submission_id, identity_hash) DO UPDATE SET severity = "
            + "CASE WHEN array_position(" + SEVERITY_ORDER + ", EXCLUDED.severity) "
            + "< array_position(" + SEVERITY_ORDER + ", finding.severity) THEN EXCLUDED.severity "
            + "ELSE finding.severity END";

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
            + "p.approval_memo_file_id, p.plan, p.target_date, p.proposed_at ";

    /**
     * What the list is narrowed to, the same for the page and the count. Each part that is not given matches everything:
     * the Scan Report (no value), the severities (all five are passed), the review state (no value) and "needs
     * attention" (false), which is a Finding with no proposal yet or a Disputed one.
     */
    private static final String FILTERED =
            "FROM finding f LEFT JOIN finding_status_proposal p ON p.id = f.current_proposal_id "
            + "WHERE f.submission_id = :submission "
            + "AND (CAST(:type AS text) IS NULL OR f.report_type = :type) "
            + "AND f.severity = ANY(CAST(:severities AS text[])) "
            + "AND (CAST(:review AS text) IS NULL OR f.review_state = :review) "
            + "AND (NOT :attention OR f.current_proposal_id IS NULL OR f.review_state = 'DISPUTED') ";

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
            + "f.review_state, " + PROPOSAL_COLUMNS
            + FILTERED
            + "ORDER BY array_position(" + SEVERITY_ORDER + ", f.severity), f.identifier, f.package_or_rule, f.seq "
            + "LIMIT :limit OFFSET :offset";

    private static final String COUNT = "SELECT count(*) " + FILTERED;

    private static final String LOCATE = "SELECT f.submission_id, f.system_id FROM finding f WHERE f.id = :id";

    /** The same, and the Finding's row is held until the transaction ends, so two proposals for it are written in turn. */
    private static final String LOCK = LOCATE + " FOR UPDATE";

    private static final String INSERT_PROPOSAL =
            "INSERT INTO finding_status_proposal (finding_id, status, evidence, justification, reason, approver_name, "
            + "approver_role, approval_memo_file_id, plan, target_date, proposed_by, proposed_at) "
            + "VALUES (:finding, :status, :evidence, :justification, :reason, :approverName, :approverRole, :memo, :plan, "
            + ":targetDate, :by, :now) RETURNING id";

    /** The Finding's latest proposal changes, and a new proposal has not been reviewed. */
    private static final String SET_CURRENT =
            "UPDATE finding SET current_proposal_id = :proposal, review_state = 'PENDING' WHERE id = :finding";

    /** A memo is a supporting evidence file of this VA Submission that has passed every check. */
    private static final String IS_READY_EVIDENCE =
            "SELECT count(*) FROM report_file WHERE id = :file AND submission_id = :submission AND kind = 'EVIDENCE' "
            + "AND pipeline_status = 'READY'";

    private static final String PROPOSALS =
            "SELECT " + PROPOSAL_COLUMNS + "FROM finding_status_proposal p WHERE p.finding_id = :finding ORDER BY p.seq DESC";

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
                        rs.getObject("proposal_id") == null ? null : proposal(rs)))
                .list();
    }

    long count(UUID submissionId, FindingFilter filter) {
        return filtered(COUNT, submissionId, filter).query(Long.class).single();
    }

    private JdbcClient.StatementSpec filtered(String sql, UUID submissionId, FindingFilter filter) {
        List<Severity> severities = filter.severities().isEmpty() ? List.of(Severity.values()) : filter.severities();
        return jdbc.sql(sql)
                .param("submission", submissionId)
                .param("type", filter.reportType() == null ? null : filter.reportType().name())
                .param("severities", severities.stream().map(Severity::name).toArray(String[]::new))
                .param("review", filter.reviewState() == null ? null : filter.reviewState().name())
                .param("attention", filter.needsAttention());
    }

    private static ProposalView proposal(ResultSet rs) throws SQLException {
        return new ProposalView(rs.getObject("proposal_id", UUID.class), FindingStatus.valueOf(rs.getString("status")),
                rs.getString("evidence"), rs.getString("justification"), rs.getString("reason"),
                rs.getString("approver_name"), rs.getString("approver_role"),
                rs.getObject("approval_memo_file_id", UUID.class), rs.getString("plan"),
                rs.getObject("target_date", LocalDate.class),
                rs.getObject("proposed_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable ReviewState reviewStateOf(@Nullable String name) {
        return name == null ? null : ReviewState.valueOf(name);
    }

    private static @Nullable Confidence confidenceOf(@Nullable String name) {
        return name == null ? null : Confidence.valueOf(name);
    }

    /** Where the Finding is, or nothing for one that does not exist. */
    Optional<Located> locate(UUID findingId) {
        return jdbc.sql(LOCATE).param("id", findingId).query(FindingStore::located).optional();
    }

    /** As {@link #locate}, and holds the Finding's row until the transaction ends. */
    Optional<Located> lock(UUID findingId) {
        return jdbc.sql(LOCK).param("id", findingId).query(FindingStore::located).optional();
    }

    private static Located located(ResultSet rs, int row) throws SQLException {
        return new Located(rs.getObject("submission_id", UUID.class), rs.getObject("system_id", UUID.class));
    }

    /** Keeps a new proposal as the Finding's current one, with its review state back to PENDING. */
    ProposalView propose(UUID findingId, ProposalFields.Parsed fields, UUID by, Instant now) {
        UUID id = jdbc.sql(INSERT_PROPOSAL)
                .param("finding", findingId)
                .param("status", fields.status().name())
                .param("evidence", fields.evidence())
                .param("justification", fields.justification())
                .param("reason", fields.reason())
                .param("approverName", fields.approverName())
                .param("approverRole", fields.approverRole())
                .param("memo", fields.approvalMemoFileId())
                .param("plan", fields.plan())
                .param("targetDate", fields.targetDate())
                .param("by", by)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .single();
        jdbc.sql(SET_CURRENT).param("proposal", id).param("finding", findingId).update();
        return new ProposalView(id, fields.status(), fields.evidence(), fields.justification(), fields.reason(),
                fields.approverName(), fields.approverRole(), fields.approvalMemoFileId(), fields.plan(),
                fields.targetDate(), now);
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
