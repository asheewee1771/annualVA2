package sg.annualva.finding;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.parser.Severity;
import sg.annualva.submission.Submissions;

/**
 * How the Scan Reports of a VA Submission are provided, and the Findings typed in by hand (ADR 0030). Constant SQL, named
 * parameters (as-2).
 */
@Repository
class ScanReportStore {

    /** One Scan Report's mode as the table holds it. */
    record Slot(ReportMode mode, @Nullable String justification, @Nullable ReviewState reviewState, int version) {

        static final Slot UPLOADED = new Slot(ReportMode.UPLOAD, null, null, 0);
    }

    /** A Finding entered by hand, ready to be kept. */
    record Manual(ReportType reportType, String key, String hash, String title, String identifier, String packageOrRule,
            Severity severity) {}

    /**
     * The row every write to a Scan Report locks: a VA Submission has none for a Scan Report until the first such write,
     * which adds it as uploaded. Two writes that add it at once both find it afterwards: the second waits on the first's
     * row and then does nothing.
     */
    private static final String ENSURE =
            "INSERT INTO report_slot (submission_id, report_type, mode) VALUES (:submission, :type, 'UPLOAD') "
            + "ON CONFLICT (submission_id, report_type) DO NOTHING";

    private static final String SLOT_COLUMNS =
            "SELECT mode, justification, review_state, justification_version FROM report_slot "
            + "WHERE submission_id = :submission "
            + "AND report_type = :type ";

    /** A write that relies on the mode (an upload, a Finding by hand) holds it so that the mode cannot change under it. */
    private static final String LOCK_SHARED = SLOT_COLUMNS + "FOR SHARE";

    /** A mode change holds the row against every other write to the Scan Report until it commits. */
    private static final String LOCK_EXCLUSIVE = SLOT_COLUMNS + "FOR UPDATE";

    /**
     * Whether the Scan Report holds anything a mode change would leave behind: a report file that was not rejected (on
     * its way through the checks, parsed or superseded), or a Finding. Supporting evidence is not a report.
     */
    private static final String HAS_CONTENT =
            "SELECT EXISTS (SELECT 1 FROM report_file WHERE submission_id = :submission AND report_type = :type "
            + "AND kind = 'MACHINE_READABLE' AND reject_code IS NULL) "
            + "OR EXISTS (SELECT 1 FROM finding WHERE submission_id = :submission AND report_type = :type)";

    /**
     * Writes the mode, the justification, its review and its version, all decided by {@link ScanReports#setMode} from the
     * row it holds.
     */
    private static final String UPDATE =
            "UPDATE report_slot SET mode = :mode, justification = CAST(:justification AS text), "
            + "review_state = CAST(:review AS text), justification_version = :version "
            + "WHERE submission_id = :submission AND report_type = :type";

    private static final String OF_SUBMISSION =
            "SELECT report_type, mode, justification, review_state, justification_version FROM report_slot "
            + "WHERE submission_id = :submission";

    /** A Finding entered by hand; nothing when the VA Submission already has one with the same identity key. */
    private static final String INSERT_MANUAL =
            "INSERT INTO finding (submission_id, system_id, report_type, identity_key, identity_hash, identifier, "
            + "package_or_rule, severity, source, title, created_at) "
            + "VALUES (:submission, :system, :type, :key, :hash, :identifier, :subject, :severity, 'MANUAL', :title, :now) "
            + "ON CONFLICT (submission_id, identity_hash) DO NOTHING RETURNING id";

    private final JdbcClient jdbc;

    ScanReportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The Scan Report's mode, its row held until the transaction ends: shared by a write that relies on the mode, or
     * exclusive for a mode change.
     */
    Slot lock(Submissions.EditTarget target, ReportType type, boolean exclusive) {
        UUID submissionId = target.submissionId();
        jdbc.sql(ENSURE).param("submission", submissionId).param("type", type.name()).update();
        return jdbc.sql(exclusive ? LOCK_EXCLUSIVE : LOCK_SHARED)
                .param("submission", submissionId)
                .param("type", type.name())
                .query((rs, row) -> new Slot(ReportMode.valueOf(rs.getString("mode")), rs.getString("justification"),
                        reviewStateOf(rs.getString("review_state")), rs.getInt("justification_version")))
                .single();
    }

    boolean hasContent(UUID submissionId, ReportType type) {
        return jdbc.sql(HAS_CONTENT).param("submission", submissionId).param("type", type.name())
                .query(Boolean.class).single();
    }

    /** Writes the mode of a Scan Report whose row the caller holds ({@link #lock}). */
    void set(Submissions.EditTarget target, ReportType type, Slot slot) {
        ReviewState review = slot.reviewState();
        jdbc.sql(UPDATE)
                .param("submission", target.submissionId())
                .param("type", type.name())
                .param("mode", slot.mode().name())
                .param("justification", slot.justification())
                .param("review", review == null ? null : review.name())
                .param("version", slot.version())
                .update();
    }

    /** The mode of each Scan Report of the VA Submission that has a row; the others are uploaded. */
    Map<ReportType, Slot> ofSubmission(UUID submissionId) {
        Map<ReportType, Slot> slots = new EnumMap<>(ReportType.class);
        jdbc.sql(OF_SUBMISSION).param("submission", submissionId)
                .query((rs, row) -> Map.entry(ReportType.valueOf(rs.getString("report_type")),
                        new Slot(ReportMode.valueOf(rs.getString("mode")), rs.getString("justification"),
                                reviewStateOf(rs.getString("review_state")), rs.getInt("justification_version"))))
                .list()
                .forEach(slot -> slots.put(slot.getKey(), slot.getValue()));
        return slots;
    }

    /** Keeps a Finding entered by hand; its id, or nothing when the VA Submission already has that Finding. */
    Optional<UUID> insertManual(Submissions.EditTarget target, Manual finding, Instant now) {
        return jdbc.sql(INSERT_MANUAL)
                .param("submission", target.submissionId())
                .param("system", target.systemId())
                .param("type", finding.reportType().name())
                .param("key", finding.key())
                .param("hash", finding.hash())
                .param("identifier", finding.identifier())
                .param("subject", finding.packageOrRule())
                .param("severity", finding.severity().name())
                .param("title", finding.title())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional();
    }

    private static @Nullable ReviewState reviewStateOf(@Nullable String name) {
        return name == null ? null : ReviewState.valueOf(name);
    }
}
