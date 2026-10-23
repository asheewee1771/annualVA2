package sg.annualva.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.finding.ReportType;
import sg.annualva.parser.ParsedReport;

/**
 * The Scan Report file tables. Constant SQL, named parameters (as-2). Every change the scan job makes is guarded by the
 * state the job read (the status and the attempts), so two instances, or a pass that read an old row, can never both
 * apply an outcome: the loser's update changes nothing and says so.
 */
@Repository
class ReportFileStore {

    /**
     * A file as the table holds it, with the System of its VA Submission.
     *
     * @param quarantineKey where the unscanned bytes are held; null once they are deleted
     * @param cleanKey where the unchanged copy is held; null unless the file passed every stage
     * @param scanTicket the scanner's handle for the current attempt; never shown or logged
     * @param scanSubmittedAt when the current attempt began, or failed to begin
     * @param replacesId the parsed report file this one is a rescan of; null for an ordinary upload
     * @param supersededBy the rescan that replaced this file; null while it has not been
     * @param scanDateConfirmed the business date the SMO/Tech Lead says the scan was made; null until they confirm it
     * @param targetConfirmed the target the SMO/Tech Lead confirmed or corrected; null until they confirm it
     */
    record Row(
            UUID id, UUID submissionId, UUID systemId, ReportType reportType, FileKind kind, String displayName,
            long sizeBytes, String uploadSha256, @Nullable String detectedFormat, @Nullable String quarantineKey,
            @Nullable String cleanKey, PipelineStatus status, @Nullable RejectCode rejectCode,
            @Nullable String scanTicket, int scanAttempts, @Nullable Instant scanSubmittedAt, UUID uploadedBy,
            Instant uploadedAt, @Nullable Instant scanDateFromFile, @Nullable String targetFromFile,
            @Nullable UUID replacesId, @Nullable UUID supersededBy, @Nullable LocalDate scanDateConfirmed,
            @Nullable String targetConfirmed) {}

    /** One entry of the order a file went through. */
    record Stage(UUID fileId, PipelineStatus status, Instant at) {}

    /** A settled file whose quarantined bytes have not been deleted yet. */
    record Leftover(UUID id, PipelineStatus status, String quarantineKey) {}

    /**
     * What the scan job writes when a file reaches a resting status: the stages it passed through in order (the last is
     * the status it rests in), why it was rejected if it was, and what the checks learnt.
     *
     * @param cleanKey the key of the unchanged copy, for a file that passed every stage
     * @param attempts the scan attempts the file has used when it rests
     * @param parsed what the parser read, for a file that was parsed: its Findings are kept in the same transaction and its
     *     scan date and target are written to the file
     */
    record Settlement(
            List<PipelineStatus> stages, @Nullable RejectCode rejectCode, @Nullable String cleanKey,
            @Nullable String cleanSha256, @Nullable String format, int attempts, @Nullable ParsedReport parsed) {

        PipelineStatus resting() {
            return stages.get(stages.size() - 1);
        }
    }

    private static final String COLUMNS =
            "SELECT f.id, f.submission_id, s.system_id, f.report_type, f.kind, f.display_name, f.size_bytes, "
            + "f.upload_sha256, f.detected_format, f.quarantine_key, f.clean_key, f.pipeline_status, f.reject_code, "
            + "f.scan_ticket, f.scan_attempts, f.scan_submitted_at, f.uploaded_by, f.uploaded_at, f.scan_date_from_file, "
            + "f.target_from_file, f.replaces_id, f.superseded_by, f.scan_date_confirmed, f.target_confirmed "
            + "FROM report_file f JOIN va_submission s ON s.id = f.submission_id ";

    private static final String FIND = COLUMNS + "WHERE f.id = :id";

    private static final String OF_SUBMISSION = COLUMNS + "WHERE f.submission_id = :submission ORDER BY f.seq";

    private static final String INSERT =
            "INSERT INTO report_file (submission_id, report_type, kind, display_name, size_bytes, upload_sha256, "
            + "quarantine_key, pipeline_status, uploaded_by, uploaded_at, updated_at, replaces_id) "
            + "VALUES (:submission, :type, :kind, :name, :size, :sha, :quarantine, 'QUARANTINED', :by, :now, :now, "
            + "CAST(:replaces AS uuid)) RETURNING id";

    private static final String INSERT_STAGE =
            "INSERT INTO report_file_stage (file_id, status, entered_at) VALUES (:file, :status, :now)";

    private static final String STAGES =
            "SELECT file_id, status, entered_at FROM report_file_stage WHERE file_id = ANY(CAST(:files AS uuid[])) "
            + "ORDER BY seq";

    /** Files that still count against a Scan Report's limit: every one but the rejected and the superseded. */
    private static final String COUNT_LIVE =
            "SELECT count(*) FROM report_file WHERE submission_id = :submission AND report_type = :type "
            + "AND kind = :kind AND reject_code IS NULL AND superseded_by IS NULL";

    /**
     * A parsed file is superseded by its rescan, in the transaction that records the rescan as parsed. Only a parsed file
     * that nothing has superseded yet; a unique index lets only one rescan of a file be live, so this always applies.
     */
    private static final String SUPERSEDE =
            "UPDATE report_file SET superseded_by = :rescan, superseded_at = :now, updated_at = :now "
            + "WHERE id = :id AND pipeline_status = 'PARSED' AND superseded_by IS NULL";

    /**
     * The scan date and target the SMO/Tech Lead confirmed, only on a parsed file that no rescan has replaced: a file that
     * is being superseded waits for the settlement that supersedes it, and then is not changed.
     */
    private static final String CONFIRM =
            "UPDATE report_file SET scan_date_confirmed = :scanDate, target_confirmed = :target, updated_at = :now "
            + "WHERE id = :id AND pipeline_status = 'PARSED' AND superseded_by IS NULL";

    /** The report files whose scan date is judged by the age rule: parsed, and not replaced by a rescan. Oldest first. */
    private static final String LIVE_PARSED = COLUMNS
            + "WHERE f.submission_id = :submission AND f.pipeline_status = 'PARSED' AND f.superseded_by IS NULL "
            + "ORDER BY f.seq";

    private static final String IN_FLIGHT =
            "SELECT id FROM report_file WHERE pipeline_status = 'SCAN_SUBMITTED' ORDER BY seq";

    private static final String COUNT_IN_FLIGHT =
            "SELECT count(*) FROM report_file WHERE pipeline_status = 'SCAN_SUBMITTED'";

    /** Quarantined files whose next attempt is due: never tried, or last tried at least an attempt timeout ago. */
    private static final String WAITING =
            "SELECT id FROM report_file WHERE pipeline_status = 'QUARANTINED' "
            + "AND (scan_submitted_at IS NULL OR scan_submitted_at <= :due) ORDER BY seq LIMIT :limit";

    /** A scan attempt began: the file is with the scanner, under a new ticket. Applies only to the row the job read. */
    private static final String ATTEMPT_STARTED =
            "UPDATE report_file SET pipeline_status = 'SCAN_SUBMITTED', scan_ticket = :ticket, "
            + "scan_attempts = scan_attempts + 1, scan_submitted_at = :now, updated_at = :now "
            + "WHERE id = :id AND pipeline_status = :from AND scan_attempts = :attempts";

    /** An attempt could not be made: it still counts, and the next one waits the attempt timeout from now. */
    private static final String ATTEMPT_FAILED =
            "UPDATE report_file SET scan_attempts = scan_attempts + 1, scan_submitted_at = :now, updated_at = :now "
            + "WHERE id = :id AND pipeline_status = :from AND scan_attempts = :attempts";

    /** The file rests in a new status: every column the checks learnt is written at once. */
    private static final String SETTLE =
            "UPDATE report_file SET pipeline_status = :status, reject_code = :code, "
            + "clean_key = COALESCE(:clean, clean_key), clean_sha256 = COALESCE(:sha, clean_sha256), "
            + "detected_format = COALESCE(:format, detected_format), scan_ticket = NULL, scan_attempts = :after, "
            + "scan_date_from_file = :scanDate, target_from_file = :target, "
            + "updated_at = :now "
            + "WHERE id = :id AND pipeline_status = :from AND scan_attempts = :attempts";

    /** Settled files that still hold quarantined bytes: the bytes are deleted after the commit that settled them. */
    private static final String LEFTOVERS =
            "SELECT id, pipeline_status, quarantine_key FROM report_file WHERE quarantine_key IS NOT NULL "
            + "AND pipeline_status NOT IN ('QUARANTINED', 'SCAN_SUBMITTED') ORDER BY updated_at, seq LIMIT 200";

    /** A delete that failed moves the file to the back of the sweep, so the files not yet tried come first. */
    private static final String DEFER_RELEASE = "UPDATE report_file SET updated_at = :now WHERE id = :id";

    private static final String CLEAR_QUARANTINE_KEY =
            "UPDATE report_file SET quarantine_key = NULL WHERE id = :id AND quarantine_key = :key";

    private final JdbcClient jdbc;

    ReportFileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a quarantined file and the first stage it entered.
     *
     * @param replacesId the parsed report file it is a rescan of, or null for an ordinary upload
     * @throws org.springframework.dao.DuplicateKeyException when that file already has a rescan on its way or parsed
     */
    UUID insert(UUID submissionId, ReportType type, FileKind kind, String displayName, long sizeBytes, String sha256,
            String quarantineKey, UUID uploadedBy, Instant now, @Nullable UUID replacesId) {
        UUID id = jdbc.sql(INSERT)
                .param("submission", submissionId)
                .param("type", type.name())
                .param("kind", kind.name())
                .param("name", displayName)
                .param("size", sizeBytes)
                .param("sha", sha256)
                .param("quarantine", quarantineKey)
                .param("by", uploadedBy)
                .param("now", utc(now))
                .param("replaces", replacesId)
                .query(UUID.class)
                .single();
        stage(id, PipelineStatus.QUARANTINED, now);
        return id;
    }

    /** @return whether the file was a parsed one that nothing had superseded */
    boolean supersede(UUID fileId, UUID rescanId, Instant now) {
        return jdbc.sql(SUPERSEDE).param("id", fileId).param("rescan", rescanId).param("now", utc(now)).update() == 1;
    }

    /** @return whether the file was still a parsed one that no rescan had replaced; false means nothing was written */
    boolean confirm(UUID fileId, LocalDate scanDate, String target, Instant now) {
        return jdbc.sql(CONFIRM).param("id", fileId).param("scanDate", scanDate).param("target", target)
                .param("now", utc(now)).update() == 1;
    }

    /** The VA Submission's parsed report files that no rescan has replaced, oldest first. */
    List<Row> liveParsed(UUID submissionId) {
        return jdbc.sql(LIVE_PARSED).param("submission", submissionId).query((rs, row) -> row(rs)).list();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query((rs, row) -> row(rs)).optional();
    }

    List<Row> ofSubmission(UUID submissionId) {
        return jdbc.sql(OF_SUBMISSION).param("submission", submissionId).query((rs, row) -> row(rs)).list();
    }

    int countLive(UUID submissionId, ReportType type, FileKind kind) {
        return jdbc.sql(COUNT_LIVE)
                .param("submission", submissionId)
                .param("type", type.name())
                .param("kind", kind.name())
                .query(Integer.class)
                .single();
    }

    /** The order each of these files went through its stages, by file. */
    Map<UUID, List<Stage>> stages(Collection<UUID> fileIds) {
        Map<UUID, List<Stage>> byFile = new HashMap<>();
        if (fileIds.isEmpty()) {
            return byFile;
        }
        jdbc.sql(STAGES)
                .param("files", fileIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, row) -> new Stage(rs.getObject("file_id", UUID.class),
                        PipelineStatus.valueOf(rs.getString("status")),
                        rs.getObject("entered_at", OffsetDateTime.class).toInstant()))
                .list()
                .forEach(stage -> byFile.computeIfAbsent(stage.fileId(), id -> new ArrayList<>()).add(stage));
        return byFile;
    }

    /** The files with the scanner, oldest first. */
    List<UUID> inFlight() {
        return jdbc.sql(IN_FLIGHT).query(UUID.class).list();
    }

    int countInFlight() {
        return jdbc.sql(COUNT_IN_FLIGHT).query(Integer.class).single();
    }

    /** The quarantined files whose next attempt is due, oldest first; {@code due} is now minus the attempt timeout. */
    List<UUID> waiting(int limit, Instant due) {
        return jdbc.sql(WAITING).param("limit", limit).param("due", utc(due)).query(UUID.class).list();
    }

    /** @return whether the file was still as the job read it; false means another pass moved it first */
    boolean attemptStarted(Row file, String ticket, Instant now) {
        boolean applied = jdbc.sql(ATTEMPT_STARTED).param("id", file.id()).param("ticket", ticket)
                .param("from", file.status().name()).param("attempts", file.scanAttempts()).param("now", utc(now))
                .update() == 1;
        if (applied) {
            stage(file.id(), PipelineStatus.SCAN_SUBMITTED, now);
        }
        return applied;
    }

    /** @return whether the file was still as the job read it */
    boolean attemptFailed(Row file, Instant now) {
        return jdbc.sql(ATTEMPT_FAILED).param("id", file.id()).param("from", file.status().name())
                .param("attempts", file.scanAttempts()).param("now", utc(now)).update() == 1;
    }

    /** @return whether the file was still as the job read it; false means nothing was written */
    boolean settle(Row file, Settlement settlement, Instant now) {
        boolean applied = jdbc.sql(SETTLE).param("id", file.id()).param("status", settlement.resting().name())
                .param("code", settlement.rejectCode() == null ? null : settlement.rejectCode().name())
                .param("clean", settlement.cleanKey()).param("sha", settlement.cleanSha256())
                .param("format", settlement.format())
                .param("from", file.status().name()).param("attempts", file.scanAttempts())
                .param("after", settlement.attempts())
                .param("scanDate", scanDate(settlement.parsed()))
                .param("target", settlement.parsed() == null ? null : settlement.parsed().metadata().target())
                .param("now", utc(now)).update() == 1;
        if (applied) {
            settlement.stages().forEach(status -> stage(file.id(), status, now));
        }
        return applied;
    }

    List<Leftover> leftovers() {
        return jdbc.sql(LEFTOVERS)
                .query((rs, row) -> new Leftover(rs.getObject("id", UUID.class),
                        PipelineStatus.valueOf(rs.getString("pipeline_status")), rs.getString("quarantine_key")))
                .list();
    }

    void deferRelease(UUID id, Instant now) {
        jdbc.sql(DEFER_RELEASE).param("id", id).param("now", utc(now)).update();
    }

    /** Forgets the quarantined bytes once they are really gone. */
    void clearQuarantineKey(UUID id, String key) {
        jdbc.sql(CLEAR_QUARANTINE_KEY).param("id", id).param("key", key).update();
    }

    private void stage(UUID fileId, PipelineStatus status, Instant now) {
        jdbc.sql(INSERT_STAGE).param("file", fileId).param("status", status.name()).param("now", utc(now)).update();
    }

    private static @Nullable OffsetDateTime scanDate(@Nullable ParsedReport parsed) {
        Instant scanned = parsed == null ? null : parsed.metadata().scanDate();
        return scanned == null ? null : utc(scanned);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String code = rs.getString("reject_code");
        OffsetDateTime submitted = rs.getObject("scan_submitted_at", OffsetDateTime.class);
        OffsetDateTime scanned = rs.getObject("scan_date_from_file", OffsetDateTime.class);
        return new Row(rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                rs.getObject("system_id", UUID.class), ReportType.valueOf(rs.getString("report_type")),
                FileKind.valueOf(rs.getString("kind")), rs.getString("display_name"), rs.getLong("size_bytes"),
                rs.getString("upload_sha256"), rs.getString("detected_format"), rs.getString("quarantine_key"),
                rs.getString("clean_key"), PipelineStatus.valueOf(rs.getString("pipeline_status")),
                code == null ? null : RejectCode.valueOf(code), rs.getString("scan_ticket"),
                rs.getInt("scan_attempts"), submitted == null ? null : submitted.toInstant(),
                rs.getObject("uploaded_by", UUID.class), rs.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                scanned == null ? null : scanned.toInstant(), rs.getString("target_from_file"),
                rs.getObject("replaces_id", UUID.class), rs.getObject("superseded_by", UUID.class),
                rs.getObject("scan_date_confirmed", LocalDate.class), rs.getString("target_confirmed"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
