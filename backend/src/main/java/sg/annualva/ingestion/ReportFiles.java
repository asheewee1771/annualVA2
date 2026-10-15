package sg.annualva.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.finding.ReportType;
import sg.annualva.scan.CleanFiles;
import sg.annualva.scan.MalwareScan;
import sg.annualva.submission.Submissions;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The Scan Report files of a VA Submission, as the SMO/Tech Lead and the people who review them see them (stories 20,
 * 24, 27 and 78). An upload is quarantined and answered at once with 202; the scan job then walks it through the malware
 * scan, the type allowlist and the schema check ({@link ScanPoller}). Nobody can read a file before it has passed them
 * all: a download answers 404 until then, for a rejected file always, and for anyone who may not see the System.
 *
 * <p>Each command checks, in this order: ownership (the access guard, so a System the caller may not reach is "not
 * found", ADR 0013), the state of the VA Submission, then the limits, and only then keeps anything. A refused upload is
 * audited in an entry of its own, written before any transaction opens (ADR 0014). Reading a file's status is audited
 * unless the caller is the System's own SMO/Tech Lead, who polls it while the file is scanned and sees no report content;
 * a download is audited for everyone (reviewer-decisions.md, ADR 0021).
 */
@Service
public class ReportFiles {

    /** The most files, not counting rejected ones, of one kind a Scan Report holds. */
    static final int MAX_FILES_PER_REPORT = 20;
    private static final int MAX_NAME_CODE_POINTS = 100;
    private static final String FALLBACK_NAME = "file";

    private final Submissions submissions;
    private final ReportFileStore store;
    private final MalwareScan scan;
    private final CleanFiles cleanFiles;
    private final AccessGuard guard;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ReportFiles(Submissions submissions, ReportFileStore store, MalwareScan scan, CleanFiles cleanFiles,
            AccessGuard guard, CurrentUser user, AuditActor actor, AuditLog audit, Clock clock,
            TransactionTemplate transaction) {
        this.submissions = submissions;
        this.store = store;
        this.scan = scan;
        this.cleanFiles = cleanFiles;
        this.guard = guard;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
        this.transaction = transaction;
    }

    /** A clean file ready to be served: its name and bytes. */
    public record Content(String name, byte[] bytes) {}

    /**
     * Quarantines an uploaded file and records it; the pipeline takes it from there.
     *
     * @param originalName the name the browser sent; cleaned before it is kept
     * @throws ResourceNotFoundException for a VA Submission the caller is not the SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no files now, 409
     *     {@code too-many-files} when the Scan Report is full, 400 {@code file-empty}, 503 {@code storage-unavailable}
     *     when the file could not be quarantined. A file over the container's 50 MB ceiling is refused with 413 before
     *     it gets here; the limit of each type is applied by the type check, after the scan
     */
    public ReportFileView upload(UUID submissionId, ReportType reportType, FileKind kind, String originalName,
            byte[] content) {
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.SUBMIT);
        if (!target.acceptsFiles()) {
            throw refused(target, "state-not-editable", HttpStatus.CONFLICT, "invalid-transition",
                    "This VA Submission takes no new files in its present state.");
        }
        if (store.countLive(submissionId, reportType, kind) >= MAX_FILES_PER_REPORT) {
            throw refused(target, "too-many-files", HttpStatus.CONFLICT, "too-many-files",
                    "This Scan Report already holds the most files it can. Ask the VA POC if one has to be replaced.");
        }
        if (content.length == 0) {
            throw new RejectedRequestException(HttpStatus.BAD_REQUEST, "file-empty", "The file is empty.");
        }
        MalwareScan.Quarantined held = quarantine(content);
        try {
            UUID id = Objects.requireNonNull(transaction.execute(status -> keep(target, reportType, kind,
                    displayName(originalName), content.length, held)));
            return view(id);
        } catch (RuntimeException e) {
            // Nothing was recorded, so nothing may stay in quarantine.
            scan.discard(held.key());
            throw e;
        }
    }

    /** The VA Submission's files, oldest first, for anyone who may see its System. */
    @Transactional
    public List<ReportFileView> list(UUID submissionId) {
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.VIEW);
        List<ReportFileStore.Row> rows = store.ofSubmission(submissionId);
        var stages = store.stages(rows.stream().map(ReportFileStore.Row::id).toList());
        auditView(target, null, rows.size());
        return rows.stream().map(row -> view(row, stages.getOrDefault(row.id(), List.of()))).toList();
    }

    /** One file's pipeline status. The file must belong to the VA Submission in the path, else it is not found. */
    @Transactional
    public ReportFileView status(UUID submissionId, UUID fileId) {
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.VIEW);
        ReportFileStore.Row row = file(submissionId, fileId);
        auditView(target, fileId, 1);
        return view(row, store.stages(List.of(fileId)).getOrDefault(fileId, List.of()));
    }

    /**
     * The scan date and target a file's report names, pre-filled for the SMO/Tech Lead to confirm. They are report content,
     * so the read is audited for every role. A file that has not passed every stage, or was rejected, is "not found", as
     * its content is.
     */
    @Transactional
    public ReportFileMetadataView metadata(UUID submissionId, UUID fileId) {
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.VIEW);
        ReportFileStore.Row row = file(submissionId, fileId);
        if (!row.status().viewable()) {
            throw new ResourceNotFoundException();
        }
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_METADATA_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId));
        // A file that passed every stage was given its format by the type check.
        boolean exportTime = ReportFormat.valueOf(Objects.requireNonNull(row.detectedFormat())).scanDateIsExportTime();
        return new ReportFileMetadataView(fileId, row.scanDateFromFile(), exportTime, row.targetFromFile());
    }

    /** What a download was granted: the name to offer and where the bytes are. */
    private record Grant(String name, String cleanKey) {}

    /**
     * The bytes of a file that passed every stage, for anyone who may see its System. A file that is not clean yet, one
     * that was rejected and one the caller may not see are all "not found". The caller is checked and the download audited
     * in a short transaction of its own, which has ended before the bytes (up to 50 MB) are read from the store, so a slow
     * download never holds a pooled connection; the entry is therefore written before anything is served.
     */
    public Content content(UUID submissionId, UUID fileId) {
        Grant grant = Objects.requireNonNull(transaction.execute(status -> grantDownload(submissionId, fileId)));
        try {
            return new Content(grant.name(), cleanFiles.get(grant.cleanKey()));
        } catch (RuntimeException e) {
            throw new RejectedRequestException(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable",
                    "The file could not be read from storage right now. Try again.");
        }
    }

    private Grant grantDownload(UUID submissionId, UUID fileId) {
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.VIEW);
        ReportFileStore.Row row = file(submissionId, fileId);
        String cleanKey = row.cleanKey();
        if (!row.status().viewable() || cleanKey == null) {
            throw new ResourceNotFoundException();
        }
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_DOWNLOADED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId));
        return new Grant(row.displayName(), cleanKey);
    }

    private ReportFileStore.Row file(UUID submissionId, UUID fileId) {
        return store.find(fileId).filter(row -> row.submissionId().equals(submissionId))
                .orElseThrow(ResourceNotFoundException::new);
    }

    private MalwareScan.Quarantined quarantine(byte[] content) {
        try {
            return scan.quarantine(content);
        } catch (RuntimeException e) {
            throw new RejectedRequestException(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable",
                    "The file could not be stored right now. Try again.");
        }
    }

    /** Records the file and audits the upload in one transaction, so the log never names a file that was not kept. */
    private UUID keep(Submissions.FileTarget target, ReportType reportType, FileKind kind, String name, long size,
            MalwareScan.Quarantined held) {
        Instant now = clock.instant();
        UUID id = store.insert(target.submissionId(), reportType, kind, name, size, held.sha256(), held.key(),
                user.id(), now);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_UPLOADED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).with(DetailKey.FILE_ID, id));
        return id;
    }

    /** Audits a read of status unless the caller is the System's own SMO/Tech Lead; a list is one entry with the count. */
    private void auditView(Submissions.FileTarget target, @Nullable UUID fileId, int count) {
        if (guard.scope(Need.SUBMIT).contains(target.systemId())) {
            return;
        }
        AuditEvent event = actor.attribute(AuditEvent.of(AuditAction.FILE_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).with(DetailKey.COUNT, count);
        audit.record(fileId == null ? event : event.with(DetailKey.FILE_ID, fileId));
    }

    private RejectedRequestException refused(Submissions.FileTarget target, String reason, HttpStatus status,
            String type, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.FILE_UPLOADED, AuditOutcome.REFUSED))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).with(DetailKey.REASON, reason));
        return new RejectedRequestException(status, type, detail);
    }

    private ReportFileView view(UUID id) {
        ReportFileStore.Row row = store.find(id).orElseThrow(ResourceNotFoundException::new);
        return view(row, store.stages(List.of(id)).getOrDefault(id, List.of()));
    }

    private static ReportFileView view(ReportFileStore.Row row, List<ReportFileStore.Stage> stages) {
        RejectCode code = row.rejectCode();
        return new ReportFileView(row.id(), row.submissionId(), row.reportType(), row.kind(), row.displayName(),
                row.sizeBytes(), row.uploadSha256(), row.status(), code, code == null ? null : code.message(), row.scanAttempts(),
                row.detectedFormat(), row.uploadedAt(),
                stages.stream().map(stage -> new ReportFileView.Stage(stage.status(), stage.at())).toList());
    }

    /**
     * The name as shown and as offered for download (IM8 as-1): the part after any path, then an allowlist: letters, digits,
     * a full stop, underscore, hyphen, parentheses and the ordinary space are kept and every other character becomes an
     * underscore, at most 100 code points. So a name can neither smuggle a header, nor carry markup or a control or
     * direction character, nor shift the layout. A name that is nothing after that is "file".
     */
    static String displayName(String original) {
        String name = original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder clean = new StringBuilder();
        name.codePoints().limit(MAX_NAME_CODE_POINTS)
                .forEach(codePoint -> clean.appendCodePoint(allowedInName(codePoint) ? codePoint : '_'));
        String trimmed = clean.toString().strip();
        return trimmed.isEmpty() ? FALLBACK_NAME : trimmed;
    }

    private static boolean allowedInName(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '.' || codePoint == '_' || codePoint == '-'
                || codePoint == '(' || codePoint == ')' || codePoint == ' ';
    }
}
