package sg.annualva.register;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.InputStreamSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.scan.MalwareScan;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The System Register upload (stories 2, 12 and 14): reads a filled-in template, works out what it would change and
 * keeps that as a preview for an hour. Nothing in the Register changes here; applying a preview is a later command
 * with a step-up of its own ({@link RegisterApply}).
 *
 * <p>The file is untrusted. It is quarantined and malware-scanned, waiting for the verdict (IM8 as-12, story 24;
 * ADR 0017), and only the scanner's clean copy is read, and refused if it is wrong, before any transaction opens, so the
 * refusal is recorded in an entry of its own (a fixed code, never content) and cannot be lost to a rollback; the preview
 * and its audit entry are then written together in one transaction. A VA POC's scope is every System, so no ownership
 * check applies (ADR 0013); the role rule on the controller is the control (ADR 0017).
 */
@Service
public class RegisterUploads {

    /** How long a preview can be read and, later, applied. */
    static final Duration PREVIEW_LIFETIME = Duration.ofHours(1);

    private final RegisterStore store;
    private final MalwareScan scan;
    private final AuditLog audit;
    private final AuditActor actor;
    private final CurrentUser user;
    private final Clock clock;
    private final TransactionTemplate transaction;

    RegisterUploads(RegisterStore store, MalwareScan scan, AuditLog audit, AuditActor actor, CurrentUser user,
            Clock clock, TransactionTemplate transaction) {
        this.store = store;
        this.scan = scan;
        this.audit = audit;
        this.actor = actor;
        this.user = user;
        this.clock = clock;
        this.transaction = transaction;
    }

    /**
     * @param size the size the container reports, so an oversized file is refused without being read
     * @param content the file's bytes
     * @throws RejectedRequestException for a file that is not a Register workbook, with a fixed problem
     */
    public Preview upload(long size, InputStreamSource content) {
        List<RegisterRow> rows;
        try {
            rows = new RegisterSheetReader().read(scanned(bytes(size, content)));
        } catch (RegisterFileException e) {
            audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.REGISTER_UPLOAD_REJECTED,
                    AuditOutcome.REFUSED).with(DetailKey.REJECT_CODE, e.reason().auditCode())));
            throw e.reason().toProblem(e.column());
        }
        try {
            return Objects.requireNonNull(transaction.execute(status -> createPreview(rows)));
        } catch (DataAccessException | TransactionException e) {
            // The driver's message may quote the file's cells; it is dropped here and never logged.
            throw new PreviewNotStoredException();
        }
    }

    /**
     * Reads a preview back for a VA POC; the read is audited.
     *
     * @throws ResourceNotFoundException for an id that is not a preview
     * @throws RejectedRequestException 410 once the hour is up
     */
    @Transactional
    public Preview preview(UUID id) {
        Preview preview = store.preview(id).orElseThrow(ResourceNotFoundException::new);
        if (preview.expired(clock.instant())) {
            throw new RejectedRequestException(HttpStatus.GONE, "preview-expired",
                    "This preview has expired. Upload the file again.");
        }
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REGISTER_PREVIEW_VIEWED, AuditOutcome.SUCCESS)
                .with(DetailKey.PREVIEW_ID, id)));
        return preview;
    }

    private Preview createPreview(List<RegisterRow> rows) {
        Instant now = clock.instant();
        Set<String> signedIn = store.signedIn(PreviewBuilder.smoEmails(rows));
        List<ExistingSystem> existing = store.systems();
        // The Register's System ID is a citext column: it, not Java, decides which IDs are the same System.
        Set<String> ids = new HashSet<>(PreviewBuilder.matchable(rows));
        existing.forEach(system -> ids.add(system.code()));
        Map<String, String> keys = store.foldedIds(ids);
        List<PreviewRow> changes = PreviewBuilder.build(rows, existing, signedIn, id -> keys.getOrDefault(id, id));
        Instant expiresAt = now.plus(PREVIEW_LIFETIME);
        UUID id = store.insertUpload(user.id(), now, expiresAt, changes);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REGISTER_PREVIEWED, AuditOutcome.SUCCESS)
                .with(DetailKey.PREVIEW_ID, id)
                .with(DetailKey.COUNT, changes.size())));
        return new Preview(id, Preview.WAITING, now, expiresAt, changes);
    }

    /** Scans the file and returns the scanner's clean copy, which is the only thing that is ever read. */
    private byte[] scanned(byte[] bytes) {
        MalwareScan.Outcome outcome = scan.scanNow(bytes, "system-register.xlsx", RegisterSheetReader.XLSX_TYPE);
        return switch (outcome) {
            case MalwareScan.Outcome.Clean clean -> clean.content();
            case MalwareScan.Outcome.Blocked blocked -> throw new RegisterFileException(UploadRejection.MALWARE);
            case MalwareScan.Outcome.Sanitized sanitized -> throw new RegisterFileException(UploadRejection.SANITIZED);
            case MalwareScan.Outcome.TimedOut timedOut -> throw new RegisterFileException(UploadRejection.SCAN_TIMEOUT);
            case MalwareScan.Outcome.Tampered tampered -> throw new RegisterFileException(UploadRejection.SCAN_MISMATCH);
        };
    }

    private static byte[] bytes(long size, InputStreamSource content) {
        if (size <= 0) {
            throw new RegisterFileException(UploadRejection.EMPTY);
        }
        if (size > RegisterSheetReader.MAX_BYTES) {
            throw new RegisterFileException(UploadRejection.TOO_LARGE);
        }
        try (var in = content.getInputStream()) {
            byte[] bytes = in.readNBytes(RegisterSheetReader.MAX_BYTES + 1);
            if (bytes.length > RegisterSheetReader.MAX_BYTES) {
                throw new RegisterFileException(UploadRejection.TOO_LARGE);
            }
            return bytes;
        } catch (IOException e) {
            throw new RegisterFileException(UploadRejection.UNREADABLE);
        }
    }
}
