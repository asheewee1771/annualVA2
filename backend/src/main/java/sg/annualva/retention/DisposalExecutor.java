package sg.annualva.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.boundary.FileStore;

/**
 * The executor (story 87): disposes of the approved list. It deletes the file-store objects the list's records hold
 * first, outside any transaction, and then, in one transaction, the records in foreign-key order, the audit entries before
 * the list's cutoff only, and writes the tombstone: one audit entry with the counts and the list's id. A file-store
 * object that cannot be removed stops the pass with nothing deleted from the database, and the next pass tries again
 * (removing what is no longer there is not an error), so no record is ever gone while its content is still held.
 *
 * <p>Log lines carry the list's id, the event and the exception's type only, never a key or a message (lm-19).
 */
@Service
public class DisposalExecutor {

    private static final Logger log = LoggerFactory.getLogger(DisposalExecutor.class);

    private final DisposalStore store;
    private final FileStore files;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate transaction;

    DisposalExecutor(DisposalStore store, FileStore files, AuditLog audit, Clock clock, TransactionTemplate transaction) {
        this.store = store;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
        this.transaction = transaction;
    }

    /** One pass: executes the approved list, if one is waiting. */
    public void execute() {
        Optional<DisposalStore.ListRow> approved = transaction.execute(status -> store.approved());
        if (approved == null || approved.isEmpty()) {
            return;
        }
        DisposalStore.ListRow list = approved.get();
        List<DisposalStore.Blob> blobs = transaction.execute(status -> store.blobs(list.id()));
        try {
            for (DisposalStore.Blob blob : blobs == null ? List.<DisposalStore.Blob>of() : blobs) {
                files.delete(blob.zone(), blob.key());
            }
        } catch (RuntimeException e) {
            log.atWarn().setMessage("A disposal could not remove its files; it is tried again on the next pass")
                    .addKeyValue("event.action", "disposal-files-failed")
                    .addKeyValue("labels.disposal_list_id", list.id().toString())
                    .addKeyValue("error.type", e.getClass().getName()).log();
            return;
        }
        transaction.executeWithoutResult(status -> {
            store.lock();
            if (store.approved().filter(waiting -> waiting.id().equals(list.id())).isEmpty()) {
                return;
            }
            Instant now = clock.instant();
            DisposalStore.Counts counts = store.dispose(list.id(), now);
            audit.record(AuditEvent.of(AuditAction.DISPOSAL_EXECUTED, AuditOutcome.SUCCESS)
                    .with(DetailKey.DISPOSAL_LIST_ID, list.id())
                    .with(DetailKey.SUBMISSIONS, counts.submissions()).with(DetailKey.FILES, counts.files())
                    .with(DetailKey.SUPERSEDED_FILES, counts.supersededFiles())
                    .with(DetailKey.EXPORTS, counts.exports()).with(DetailKey.SYSTEMS, counts.systems())
                    .with(DetailKey.AUDIT_ENTRIES, counts.auditEntries()));
        });
        log.atInfo().setMessage("A disposal list was executed").addKeyValue("event.action", "disposal-executed")
                .addKeyValue("labels.disposal_list_id", list.id().toString()).log();
    }
}
