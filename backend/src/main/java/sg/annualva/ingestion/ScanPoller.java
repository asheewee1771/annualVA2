package sg.annualva.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.access.AuditActor;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.boundary.MalwareScanner.Verdict;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.finding.Findings;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.finding.ProposalView;
import sg.annualva.parser.ParsedReport;
import sg.annualva.scan.CleanFiles;
import sg.annualva.scan.MalwareScan;
import sg.annualva.scan.ScanProperties;

/**
 * Walks quarantined Scan Report files through the rest of the pipeline (stories 20, 20a, 24, 26, 27): the malware scan,
 * then the type allowlist, the schema check, the parse and the age check ({@link FileChecks}) on the scanner's unchanged
 * copy, the age judged against today's business date by the {@code Clock} bean. The
 * stages run in that fixed order, and a file that fails one never reaches the next. A pass:
 * <ol>
 *   <li>asks the scanner about every file that is out with it: blocked, sanitised (the scanner had to change it, which is
 *       not accepted) and a result that does not match the upload are rejected; unchanged is checked and kept; still
 *       processing past the attempt timeout, or an error, is submitted again, and after the third attempt the file is
 *       rejected as a scan timeout;
 *   <li>then submits the oldest quarantined files whose next attempt is due, as many as keep at most {@code maxInFlight}
 *       files with the scanner;
 *   <li>then deletes the quarantined bytes of every settled file.
 * </ol>
 *
 * <p>No remote call is made inside a database transaction. A file is read, the scanner and the store are called with
 * nothing held, and the outcome is written in one short transaction, guarded by the status and attempts that were read,
 * so a pass that lost a race changes nothing. Bytes are deleted only after that transaction has committed and the key is
 * forgotten only once the delete has succeeded, so a rollback never leaves a row without its bytes and a failed delete,
 * malware included, is retried on the next pass. A file the scanner or the store keeps failing on counts those failures
 * as attempts and is rejected after the third, so one bad file never holds a slot for ever. A failed submission is
 * paced like a failed check: the next attempt waits the attempt timeout, so a scanner that is down is not tried three
 * times in a minute. The attempt timeout is read from the {@code Clock} bean. Log lines carry file ids, statuses and
 * attempt counts only: never a name, a key, a ticket or an address (lm-19).
 */
@Service
public class ScanPoller {

    private static final Logger log = LoggerFactory.getLogger(ScanPoller.class);
    /** The application decides a file's type from its bytes, so it has no declared type to hand the scanner. */
    private static final String HINT = "application/octet-stream";

    private final ReportFileStore store;
    private final MalwareScan scan;
    private final CleanFiles cleanFiles;
    private final ScanProperties properties;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Findings findings;
    private final CycleCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final KeySignals signals;

    ScanPoller(ReportFileStore store, MalwareScan scan, CleanFiles cleanFiles, ScanProperties properties,
            AuditLog audit, AuditActor actor, Findings findings, CycleCalendar calendar, Clock clock,
            TransactionTemplate transaction, KeySignals signals) {
        this.signals = signals;
        this.store = store;
        this.scan = scan;
        this.cleanFiles = cleanFiles;
        this.properties = properties;
        this.audit = audit;
        this.actor = actor;
        this.findings = findings;
        this.calendar = calendar;
        this.clock = clock;
        this.transaction = transaction;
    }

    /** One pass over the files out with the scanner, the files waiting for it, and the bytes left to delete. */
    public void poll() {
        for (UUID id : store.inFlight()) {
            handled(id, () -> advance(id));
        }
        int free = properties.maxInFlight() - store.countInFlight();
        if (free > 0) {
            Instant due = clock.instant().minus(properties.attemptTimeout());
            for (UUID id : store.waiting(free, due)) {
                handled(id, () -> begin(id));
            }
        }
        Instant swept = clock.instant();
        for (ReportFileStore.Leftover leftover : store.leftovers()) {
            handled(leftover.id(), () -> release(leftover, swept));
        }
    }

    /** A file that fails here is logged by id and left for the next pass; it never stops the others. */
    private static void handled(UUID id, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.atError().setMessage("A Scan Report file could not be advanced")
                    .addKeyValue("event.action", "file-pipeline-failed")
                    .addKeyValue("labels.file_id", id.toString())
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .log();
        }
    }

    /** First attempt for a quarantined file whose attempt is due. */
    private void begin(UUID id) {
        Optional<ReportFileStore.Row> file = store.find(id);
        if (file.isPresent() && file.get().status() == PipelineStatus.QUARANTINED) {
            startAttempt(file.get(), clock.instant());
        }
    }

    /** The verdict for a file that is out with the scanner. */
    private void advance(UUID id) {
        Optional<ReportFileStore.Row> found = store.find(id);
        if (found.isEmpty() || found.get().status() != PipelineStatus.SCAN_SUBMITTED) {
            return;
        }
        ReportFileStore.Row file = found.get();
        Instant now = clock.instant();
        switch (verdictOf(file)) {
            case BLOCKED -> reject(file, RejectCode.MALWARE, now);
            case CLEAN_SANITIZED -> reject(file, RejectCode.SANITIZED, now);
            case CLEAN_UNCHANGED -> accept(file, now);
            case ERROR -> {
                signals.scanFailed();
                retryOrGiveUp(file, now);
            }
            case PROCESSING -> {
                if (expired(file, now)) {
                    retryOrGiveUp(file, now);
                }
            }
        }
    }

    /** A scanner that cannot be asked, for whatever reason, is as good as one still working: the attempt runs out. */
    private Verdict verdictOf(ReportFileStore.Row file) {
        try {
            return scan.check(Objects.requireNonNull(file.scanTicket()));
        } catch (RuntimeException e) {
            signals.scanFailed();
            return Verdict.PROCESSING;
        }
    }

    private boolean expired(ReportFileStore.Row file, Instant now) {
        Instant began = Objects.requireNonNull(file.scanSubmittedAt());
        return !now.isBefore(began.plus(properties.attemptTimeout()));
    }

    /**
     * The scanner passed the file unchanged: fetch its copy, type- and schema-check it (nothing is stored for a file the
     * checks refuse), keep the copy, then write the whole outcome at once.
     */
    private void accept(ReportFileStore.Row file, Instant now) {
        byte[] bytes;
        try {
            Optional<byte[]> unchanged = scan.fetchUnchanged(Objects.requireNonNull(file.scanTicket()),
                    file.uploadSha256());
            if (unchanged.isEmpty()) {
                reject(file, RejectCode.HASH_MISMATCH, now);
                return;
            }
            bytes = unchanged.get();
        } catch (RuntimeException e) {
            // The verdict stands but the copy could not be fetched: try again next pass, until the attempt is due.
            signals.scanFailed();
            retryWhenDue(file, now);
            return;
        }
        String cleanSha = MalwareScan.sha256(bytes);
        LocalDate today = calendar.dateOf(now);
        FileChecks.Outcome checked = FileChecks.run(file.reportType(), file.kind(), bytes,
                scanned -> calendar.dateOf(scanned).isAfter(today));
        if (!checked.passed()) {
            settle(file, new ReportFileStore.Settlement(checked.stages(), checked.rejection(), null, cleanSha,
                    checked.format(), file.scanAttempts(), null), now);
            return;
        }
        String cleanKey;
        try {
            cleanKey = cleanFiles.put(file.id(), bytes);
        } catch (RuntimeException e) {
            signals.scanFailed();
            retryWhenDue(file, now);
            return;
        }
        boolean written;
        try {
            written = settle(file, new ReportFileStore.Settlement(checked.stages(), null, cleanKey, cleanSha,
                    checked.format(), file.scanAttempts(), checked.parsed()), now);
        } catch (RuntimeException e) {
            // Nothing was recorded (a rollback), so no row points at the copy: it is not kept.
            cleanFiles.discard(cleanKey);
            throw e;
        }
        if (!written) {
            dropLosersCopy(file.id(), cleanKey, now);
        }
    }

    /**
     * Another pass settled the file first. The clean copy is stored under the file's own id, so the winner may be using
     * the very bytes this pass just wrote: they are removed only if the file now does not hold them (the winner rejected
     * it, or started another attempt). A delete that fails is left to the sweep of a rejected file only while that file's
     * quarantine key is still held; once the winner has swept and cleared it, nothing in the application can find the
     * copy, and it is for the quarantine bucket's lifecycle expiry (ticket 51).
     */
    private void dropLosersCopy(UUID id, String cleanKey, Instant now) {
        boolean held = store.find(id).map(row -> cleanKey.equals(row.cleanKey())).orElse(false);
        if (!held && !cleanFiles.discard(cleanKey)) {
            log.atWarn().setMessage("A clean copy left by a lost race could not be removed")
                    .addKeyValue("event.action", "clean-discard-failed")
                    .addKeyValue("labels.file_id", id.toString()).log();
        }
    }

    /** The copy could not be fetched or kept: nothing is lost, so wait for the next pass until the attempt is due. */
    private void retryWhenDue(ReportFileStore.Row file, Instant now) {
        if (expired(file, now)) {
            retryOrGiveUp(file, now);
        }
    }

    /** Another attempt, unless the three the spec allows are used up. */
    private void retryOrGiveUp(ReportFileStore.Row file, Instant now) {
        if (file.scanAttempts() >= MalwareScan.MAX_ATTEMPTS) {
            reject(file, RejectCode.SCAN_TIMEOUT, now);
        } else {
            startAttempt(file, now);
        }
    }

    /**
     * Submits the file to the scanner. A submission that fails for any reason still counts as an attempt and is paced, and
     * the third failure rejects the file.
     */
    private void startAttempt(ReportFileStore.Row file, Instant now) {
        String ticket;
        try {
            ticket = scan.submit(Objects.requireNonNull(file.quarantineKey()), file.displayName(), HINT);
        } catch (RuntimeException e) {
            signals.scanFailed();
            failAttempt(file, now);
            return;
        }
        if (transaction.execute(status -> store.attemptStarted(file, ticket, now)) == Boolean.TRUE) {
            logged(file.id(), PipelineStatus.SCAN_SUBMITTED, file.scanAttempts() + 1);
        }
    }

    private void failAttempt(ReportFileStore.Row file, Instant now) {
        int attempts = file.scanAttempts() + 1;
        if (attempts >= MalwareScan.MAX_ATTEMPTS) {
            // The failed attempt is the last one allowed: it counts, and the file is rejected with it.
            settle(file, new ReportFileStore.Settlement(List.of(PipelineStatus.REJECTED_SCAN_TIMEOUT),
                    RejectCode.SCAN_TIMEOUT, null, null, null, attempts, null), now);
            return;
        }
        if (transaction.execute(status -> store.attemptFailed(file, now)) == Boolean.TRUE) {
            logged(file.id(), file.status(), attempts);
        }
    }

    private void reject(ReportFileStore.Row file, RejectCode code, Instant now) {
        settle(file, new ReportFileStore.Settlement(List.of(PipelineStatus.rejected(code)), code, null, null, null,
                file.scanAttempts(), null), now);
    }

    /**
     * Writes the outcome, and for a rejection its audit entry, in one transaction.
     *
     * @return whether it was written; false means another pass moved the file first and nothing changed
     */
    private boolean settle(ReportFileStore.Row file, ReportFileStore.Settlement settlement, Instant now) {
        boolean written = transaction.execute(status -> {
            if (!store.settle(file, settlement, now)) {
                return false;
            }
            ParsedReport parsed = settlement.parsed();
            if (parsed != null) {
                // With the status, in this transaction: a file is never PARSED without its Findings, or the other way round.
                keepFindings(file, parsed, now);
            }
            RejectCode code = settlement.rejectCode();
            if (code != null) {
                audit.record(AuditEvent.of(AuditAction.FILE_REJECTED, AuditOutcome.REFUSED)
                        .onSystem(file.systemId()).onSubmission(file.submissionId())
                        .with(DetailKey.FILE_ID, file.id()).with(DetailKey.REJECT_CODE, code.auditCode()));
            }
            return true;
        }) == Boolean.TRUE;
        if (written) {
            if (settlement.rejectCode() == RejectCode.SCAN_TIMEOUT) {
                signals.scanTimedOut();
            }
            logged(file.id(), settlement.resting(), file.scanAttempts());
        }
        return written;
    }

    /**
     * Keeps what the parser read. A rescan (story 39) instead supersedes the file it replaces first, then takes the
     * Findings over from it and is audited as the replacement, all in the same transaction. A unique index lets only one
     * rescan of a file be live, so nothing else can have superseded it; if something had, the settlement stops before it
     * writes a Finding and rolls back.
     */
    private void keepFindings(ReportFileStore.Row file, ParsedReport parsed, Instant now) {
        UUID replaced = file.replacesId();
        if (replaced == null) {
            findings.record(file.submissionId(), file.systemId(), file.reportType(), file.id(), file.uploadedBy(), parsed,
                    now);
            return;
        }
        if (!store.supersede(replaced, file.id(), now)) {
            throw new IllegalStateException("The replaced file was no longer a parsed file that nothing superseded");
        }
        String replacedName = store.find(replaced).orElseThrow().displayName();
        findings.replace(file.submissionId(), file.systemId(), file.reportType(),
                new Findings.Rescan(file.id(), file.displayName(), parsed.metadata().scanDate(), file.uploadedAt(),
                        file.uploadedBy(), new ProposalView.ReplacedFile(replaced, replacedName)), parsed, now);
        // The job writes it, for the SMO/Tech Lead whose upload of the rescan caused it.
        AuditEvent replacement = AuditEvent.of(AuditAction.FILE_REPLACED, AuditOutcome.SUCCESS);
        audit.record(actor.onBehalfOf(replacement, file.uploadedBy())
                .onSystem(file.systemId()).onSubmission(file.submissionId())
                .with(DetailKey.FILE_ID, file.id()).with(DetailKey.REPLACED_FILE_ID, replaced));
    }

    /**
     * Deletes the quarantined bytes of a settled file (and, for a rejected one, any copy a failed pass left in the clean
     * zone) and only then forgets the key: a delete that fails leaves the key, and the next pass tries again, after the
     * files that were not tried yet (so files whose delete keeps failing never starve the others).
     */
    private void release(ReportFileStore.Leftover leftover, Instant now) {
        if (!scan.discard(leftover.quarantineKey())
                || (leftover.status().rejected() && !cleanFiles.discard(leftover.id().toString()))) {
            store.deferRelease(leftover.id(), now);
            return;
        }
        store.clearQuarantineKey(leftover.id(), leftover.quarantineKey());
    }

    private static void logged(UUID id, PipelineStatus status, int attempts) {
        log.atInfo().setMessage("Scan Report file moved on")
                .addKeyValue("event.action", "file-status")
                .addKeyValue("labels.file_id", id.toString())
                .addKeyValue("labels.status", status.name())
                .addKeyValue("labels.attempts", attempts)
                .log();
    }
}
