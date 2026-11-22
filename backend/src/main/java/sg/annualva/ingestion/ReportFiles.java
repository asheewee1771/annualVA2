package sg.annualva.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
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
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.finding.Findings;
import sg.annualva.finding.ProposalView;
import sg.annualva.finding.ReportType;
import sg.annualva.finding.ScanReports;
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
    private static final String NOT_REPLACEABLE =
            "Only a report file whose findings were read, and that no rescan has replaced, can be replaced.";

    private final Submissions submissions;
    private final ScanReports scanReports;
    private final Findings findings;
    private final ReportFileStore store;
    private final MalwareScan scan;
    private final CleanFiles cleanFiles;
    private final AccessGuard guard;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final CycleCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ReportFiles(Submissions submissions, ScanReports scanReports, Findings findings, ReportFileStore store,
            MalwareScan scan, CleanFiles cleanFiles, AccessGuard guard, CurrentUser user, AuditActor actor, AuditLog audit,
            CycleCalendar calendar, Clock clock, TransactionTemplate transaction) {
        this.submissions = submissions;
        this.scanReports = scanReports;
        this.findings = findings;
        this.store = store;
        this.scan = scan;
        this.cleanFiles = cleanFiles;
        this.guard = guard;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.calendar = calendar;
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
        Submissions.ReadTarget target = submissions.precheckEdit(submissionId, notEditable(null));
        if (store.countLive(submissionId, reportType, kind) >= MAX_FILES_PER_REPORT) {
            throw refused(target, null, "too-many-files", HttpStatus.CONFLICT, "too-many-files",
                    "This Scan Report already holds the most files of this kind it can. "
                    + "A report file can be replaced with its rescan instead.");
        }
        return quarantinedAndKept(target, reportType, kind, originalName, content, null);
    }

    /**
     * Takes a rescan of one parsed report file (story 39). It is quarantined and walks the whole pipeline like any upload,
     * and the file it replaces is left exactly as it is until the rescan is parsed: only then does the scan job move the
     * Findings to it and mark the old file superseded ({@link ScanPoller}). A rescan that is rejected changes nothing, and
     * the file can be replaced again. It takes the old file's place, so it is not counted against the Scan Report's 20
     * files. The checks run in the order ownership, the file, the state of the VA Submission, whether the file can be
     * replaced, then the content; a refusal after the file was found is audited as a refused upload.
     *
     * @throws ResourceNotFoundException for a VA Submission the caller is not the SMO/Tech Lead of, or a file that is not
     *     one of its own
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no files now, 409
     *     {@code file-not-replaceable} for a file that is not a parsed report file or was replaced already, 409
     *     {@code replace-in-progress} when a rescan of the file is already on its way, 400 {@code file-empty}, 503
     *     {@code storage-unavailable}
     */
    public ReportFileView replace(UUID submissionId, UUID fileId, String originalName, byte[] content) {
        // A file of another SMO's VA Submission is "not found" here or by the ownership check: the same answer either way.
        ReportFileStore.Row replaced = file(submissionId, fileId);
        Submissions.ReadTarget target = submissions.precheckEdit(submissionId, notEditable(replaced.id()));
        // Only a machine-readable file is ever PARSED (supporting evidence rests in READY, V13).
        if (replaced.status() != PipelineStatus.PARSED || replaced.supersededBy() != null) {
            throw refused(target, replaced.id(), "not-replaceable", HttpStatus.CONFLICT, "file-not-replaceable",
                    NOT_REPLACEABLE);
        }
        try {
            return quarantinedAndKept(target, replaced.reportType(), FileKind.MACHINE_READABLE, originalName, content,
                    replaced.id());
        } catch (DuplicateKeyException e) {
            // The table lets one rescan of a file be live at a time, so two requests that race are settled there too.
            throw refused(target, replaced.id(), "replace-in-progress", HttpStatus.CONFLICT, "replace-in-progress",
                    "A rescan of this file is already being checked. Wait for it to finish.");
        }
    }

    /**
     * Deletes a report file uploaded by mistake (ticket 60, ADR 0061) in any status: on its way through the checks,
     * rejected, parsed (a parsed rescan included), or superseded by a rescan that was deleted since (it stays superseded;
     * the user's decision of 2026-11-22). It is a soft delete: the row and its content are kept until the VA Cycle is
     * disposed of, and nothing lists, reads, counts or scans it any more. In the same transaction its places go and the
     * Findings it reported are dealt with as {@link Findings#fileDeleted} says. The checks run in the order the file (one
     * that is not of this VA Submission, or is deleted already, is "not found"), ownership (another SMO's VA Submission is
     * the same 404), the state of the VA Submission, then, in the one statement that marks it, that it is a report file
     * that no live rescan supersedes; a refusal after the file was found is audited in an entry of its own with the file's
     * id and a fixed reason. The deletion is a {@code FILE_DELETED} entry with the file's id, its Scan Report and the
     * number of Findings removed.
     *
     * @throws ResourceNotFoundException for a file that is not one of this VA Submission's (or was deleted, a concurrent
     *     deletion included), or a VA Submission the caller is not the SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no changes now, 409
     *     {@code file-not-deletable} for supporting evidence or a file a rescan that is not deleted has replaced
     */
    @Transactional
    public void delete(UUID submissionId, UUID fileId) {
        ReportFileStore.Row row = file(submissionId, fileId);
        Submissions.EditTarget target = submissions.lockForEdit(submissionId, (submission, system) ->
                refusedDeletion(submission, system, fileId, "state-not-editable", "invalid-transition",
                        "This VA Submission's files cannot be changed in its present state."));
        // One statement decides it, with the row held: a settlement of it, or the supersede of it by a rescan, waits for
        // the deletion or has won already, and then the statement changes nothing.
        if (!store.delete(target, fileId, user.id(), clock.instant())) {
            // Read again after the statement, so the answer is about the row as it now is: another deletion of it that
            // committed first is the same "not found" a later request gets.
            if (store.find(fileId).map(current -> current.deletedAt() != null).orElse(true)) {
                throw new ResourceNotFoundException();
            }
            throw refusedDeletion(submissionId, target.systemId(), fileId, "not-deletable", "file-not-deletable",
                    "Only a report file can be deleted, and one a rescan has replaced only once that rescan is deleted. "
                    + "Supporting evidence is kept.");
        }
        int removed = findings.fileDeleted(target, new ProposalView.ReplacedFile(fileId, row.displayName()),
                row.uploadedAt());
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_DELETED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId)
                .with(DetailKey.REPORT_TYPE, row.reportType().name()).with(DetailKey.COUNT, removed));
    }

    /** A refused deletion, audited in an entry of its own with the file and a fixed reason. */
    private RejectedRequestException refusedDeletion(UUID submissionId, UUID systemId, UUID fileId, String reason,
            String type, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.FILE_DELETED, AuditOutcome.REFUSED))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId)
                .with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, type, detail);
    }

    private ReportFileView quarantinedAndKept(Submissions.ReadTarget target, ReportType reportType, FileKind kind,
            String originalName, byte[] content, @Nullable UUID replacesId) {
        if (content.length == 0) {
            throw new RejectedRequestException(HttpStatus.BAD_REQUEST, "file-empty", "The file is empty.");
        }
        MalwareScan.Quarantined held = quarantine(content);
        try {
            UUID id = Objects.requireNonNull(transaction.execute(status -> keep(target, reportType, kind,
                    displayName(originalName), content.length, held, replacesId)));
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
        Submissions.ReadTarget target = submissions.readTarget(submissionId);
        List<ReportFileStore.Row> rows = store.ofSubmission(submissionId);
        var stages = store.stages(rows.stream().map(ReportFileStore.Row::id).toList());
        auditView(target, null, rows.size());
        return rows.stream().map(row -> view(row, stages.getOrDefault(row.id(), List.of()))).toList();
    }

    /** One file's pipeline status. The file must belong to the VA Submission in the path, else it is not found. */
    @Transactional
    public ReportFileView status(UUID submissionId, UUID fileId) {
        Submissions.ReadTarget target = submissions.readTarget(submissionId);
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
        Submissions.ReadTarget target = submissions.readTarget(submissionId);
        ReportFileStore.Row row = parsedFile(submissionId, fileId);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_METADATA_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId));
        return metadataView(row);
    }

    /**
     * Keeps the scan date and target the SMO/Tech Lead confirms or corrects for a parsed report file (story 25), beside what
     * the file says. A scan date or target that differs from the file's is flagged, never refused, and the confirmed date
     * is the one the age rule judges. The checks run in the order
     * ownership and the file (a VA Submission of a System the caller is not SMO/Tech Lead of, or a file of it that is not
     * parsed, is "not found"), the state of the VA Submission (the refusal is audited, ADR 0014), the fields (400, not
     * audited), and last, in the statement that writes it, that no rescan has replaced the file (audited too); the
     * confirmation is audited with the file's id and whether the date and the target differ from the file's, never the
     * values.
     *
     * @throws ResourceNotFoundException as above
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission takes no changes now, 409
     *     {@code file-superseded} for a file a rescan has replaced, 400 {@code invalid-field} naming {@code scanDate} (missing,
     *     not a date written yyyy-mm-dd, after today or more than ten years ago) or {@code target} (missing, empty once
     *     cleaned, or longer than 200 characters)
     */
    @Transactional
    public ReportFileMetadataView confirmMetadata(UUID submissionId, UUID fileId, ReportFileMetadataRequest request) {
        // A file of another SMO's VA Submission is "not found" here or by the ownership check: the same answer either way.
        ReportFileStore.Row row = parsedFile(submissionId, fileId);
        // Held shared until the confirmation commits: a submit judging the scan dates cannot run in between (ADR 0032).
        Submissions.EditTarget target = submissions.lockForEdit(submissionId, (submission, system) ->
                refusedConfirmation(submission, system, fileId, "state-not-editable", "invalid-transition",
                        "This VA Submission's files cannot be changed in its present state."));
        MetadataFields.Parsed fields = MetadataFields.parse(request, calendar.today());
        if (!store.confirm(target, row.id(), fields.scanDate(), fields.target(), clock.instant())) {
            // Written only while no rescan has replaced the file, checked in the one statement that writes it.
            throw refusedConfirmation(submissionId, target.systemId(), fileId, "superseded", "file-superseded",
                    "A rescan has replaced this file, which is kept as it was. Confirm the rescan's scan date and target "
                    + "instead.");
        }
        // The row was written in this transaction just now, so it is there to read back.
        ScanFacts facts = ScanFacts.of(store.find(fileId).orElseThrow(), calendar);
        // Whether the date and the target differ from the file's, as flags: never the values (reviewer decisions).
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_METADATA_CONFIRMED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId)
                .with(DetailKey.DATE_CHANGED, facts.scanDateEdited()).with(DetailKey.TARGET_CHANGED, facts.targetEdited()));
        return facts.view(fileId);
    }

    /** A file of this VA Submission that was parsed; any other is "not found": only a parsed file says anything of a scan. */
    private ReportFileStore.Row parsedFile(UUID submissionId, UUID fileId) {
        ReportFileStore.Row row = file(submissionId, fileId);
        if (row.status() != PipelineStatus.PARSED) {
            // Supporting evidence is never read, and a file still in the pipeline or rejected has nothing to say.
            throw new ResourceNotFoundException();
        }
        return row;
    }

    private ReportFileMetadataView metadataView(ReportFileStore.Row row) {
        return ScanFacts.of(row, calendar).view(row.id());
    }

    /** A refused confirmation, audited in an entry of its own with the file and a fixed reason. */
    private RejectedRequestException refusedConfirmation(UUID submissionId, UUID systemId, UUID fileId, String reason,
            String type, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.FILE_METADATA_CONFIRMED, AuditOutcome.REFUSED))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId)
                .with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, type, detail);
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
        Submissions.ReadTarget target = submissions.readTarget(submissionId);
        ReportFileStore.Row row = file(submissionId, fileId);
        String cleanKey = row.cleanKey();
        if (!row.status().viewable() || cleanKey == null) {
            throw new ResourceNotFoundException();
        }
        if (row.contentDisposedAt() != null) {
            audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.FILE_DOWNLOADED, AuditOutcome.REFUSED))
                    .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId)
                    .with(DetailKey.REASON, "disposed"));
            throw new RejectedRequestException(HttpStatus.GONE, "disposed",
                    "This file was superseded and its content has been disposed of under the retention rules. Its "
                            + "record is kept; download the file that replaced it.");
        }
        audit.record(actor.attribute(AuditEvent.of(AuditAction.FILE_DOWNLOADED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId).with(DetailKey.FILE_ID, fileId));
        return new Grant(downloadName(row), cleanKey);
    }

    /**
     * The name a download is offered under. For supporting evidence the extension comes from what the bytes were found to
     * be, never from the name the person gave: HTML saved as a .hta, .svg or .wsf file would be run by the operating system
     * rather than opened in a browser, outside everything the download headers do. A machine-readable file keeps its name.
     */
    static String downloadName(ReportFileStore.Row row) {
        String name = row.displayName();
        String format = row.detectedFormat();
        if (row.kind() != FileKind.EVIDENCE || format == null) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot).strip();
        return (base.isEmpty() ? FALLBACK_NAME : base) + EvidenceFormat.valueOf(format).extension();
    }

    /** A file of this VA Submission; one of another, or one that was deleted, is "not found". */
    private ReportFileStore.Row file(UUID submissionId, UUID fileId) {
        return store.find(fileId).filter(row -> row.submissionId().equals(submissionId) && row.deletedAt() == null)
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

    /**
     * Records the file and audits the upload in one transaction, so the log never names a file that was not kept. A report
     * file goes only to a Scan Report that is uploaded, not one marked not applicable or entered by hand (ticket 22); the
     * Scan Report is held until the file is kept, so its mode cannot change in between.
     */
    private UUID keep(Submissions.ReadTarget checked, ReportType reportType, FileKind kind, String name, long size,
            MalwareScan.Quarantined held, @Nullable UUID replacesId) {
        // Checked again now that the bytes are held: the VA Submission is held shared until the file is kept, so a submit
        // that came in meanwhile is either seen here or waits for this file (ADR 0032).
        Submissions.EditTarget target = submissions.lockForEdit(checked.submissionId(), notEditable(replacesId));
        if (kind == FileKind.MACHINE_READABLE && !scanReports.takesReportFiles(target, reportType)) {
            throw refused(target.submissionId(), target.systemId(), replacesId, "wrong-report-mode", HttpStatus.CONFLICT,
                    "wrong-report-mode",
                    "This Scan Report is marked not applicable or for Findings entered by hand, so it takes no report "
                    + "files. Supporting evidence can still be attached.");
        }
        Instant now = clock.instant();
        // A live rescan of the same file is refused here, by the one-live-rescan index (replace-in-progress).
        UUID id = store.insert(target, reportType, kind, name, size, held.sha256(), held.key(),
                user.id(), now, replacesId);
        // Then the rescan's file is held until the rescan is kept, and checked again: between the first check and now a
        // rescan of it may have superseded it and been deleted (ticket 60), which frees that index, and a rescan kept for a
        // superseded file could never settle. The row is taken only after the insert, so this never holds the file while
        // waiting on another rescan's index entry (whose settlement holds that rescan and waits for this file); a
        // settlement that supersedes the file, or a deletion of it, waits here instead. The refusal rolls the insert back.
        if (replacesId != null && !store.lockReplaceable(replacesId)) {
            throw refused(target.submissionId(), target.systemId(), replacesId, "not-replaceable", HttpStatus.CONFLICT,
                    "file-not-replaceable", NOT_REPLACEABLE);
        }
        AuditEvent uploaded = actor.attribute(AuditEvent.of(AuditAction.FILE_UPLOADED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).with(DetailKey.FILE_ID, id);
        audit.record(replacesId == null ? uploaded : uploaded.with(DetailKey.REPLACED_FILE_ID, replacesId));
        return id;
    }

    /** Audits a read of status unless the caller is the System's own SMO/Tech Lead; a list is one entry with the count. */
    private void auditView(Submissions.ReadTarget target, @Nullable UUID fileId, int count) {
        if (guard.scope(Need.SUBMIT).contains(target.systemId())) {
            return;
        }
        AuditEvent event = actor.attribute(AuditEvent.of(AuditAction.FILE_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).with(DetailKey.COUNT, count);
        audit.record(fileId == null ? event : event.with(DetailKey.FILE_ID, fileId));
    }

    /**
     * The refusal of an upload or a rescan to a VA Submission that takes no files in its present state, for
     * {@link Submissions#precheckEdit} and {@link Submissions#lockForEdit}: one sentence for both checks.
     */
    private Submissions.Refusal notEditable(@Nullable UUID replacedFileId) {
        return (submission, system) -> refused(submission, system, replacedFileId, "state-not-editable",
                HttpStatus.CONFLICT, "invalid-transition", "This VA Submission takes no new files in its present state.");
    }

    private RejectedRequestException refused(Submissions.ReadTarget target, @Nullable UUID replacedFileId, String reason,
            HttpStatus status, String type, String detail) {
        return refused(target.submissionId(), target.systemId(), replacedFileId, reason, status, type, detail);
    }

    /** A refused upload, audited with its reason and, for a rescan, the file it was to replace. */
    private RejectedRequestException refused(UUID submissionId, UUID systemId, @Nullable UUID replacedFileId,
            String reason, HttpStatus status, String type, String detail) {
        AuditEvent refusal = actor.attribute(AuditEvent.of(AuditAction.FILE_UPLOADED, AuditOutcome.REFUSED))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.REASON, reason);
        audit.recordIndependently(
                replacedFileId == null ? refusal : refusal.with(DetailKey.REPLACED_FILE_ID, replacedFileId));
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
                row.detectedFormat(), row.uploadedAt(), row.replacesId(), row.supersededBy(), row.contentDisposedAt(),
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
