package sg.annualva.export;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.annualva.boundary.FileStore;

/**
 * The export job (ADR 0043): makes the files of queued exports and removes those whose 24 hours are up. A pass:
 * <ol>
 *   <li>makes the file of each of the oldest queued exports from what was read when it was asked for, keeps it in the
 *       file store's export zone (encrypted at rest) under the export's own id, and marks it ready for 24 hours; one the
 *       store refuses is marked failed, and its requester asks again;
 *   <li>then removes the file of every ready export whose 24 hours are up and marks it expired; one the store cannot
 *       remove stays ready, is no longer served, and is tried again on the next pass.
 * </ol>
 *
 * <p>No file store call is made inside a database transaction, and each move is guarded by the status it moves from, so
 * two passes that reach the same export (two instances) write the same bytes under the same key and change its row
 * once. Log lines carry export ids and outcomes only: never what an export holds (lm-19).
 */
@Service
public class ExportWorker {

    /** How long a made file is kept and served. */
    static final Duration KEPT_FOR = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ExportWorker.class);
    private static final int BATCH = 20;

    private final ExportStore store;
    private final FileStore files;
    private final Clock clock;

    ExportWorker(ExportStore store, FileStore files, Clock clock) {
        this.store = store;
        this.files = files;
        this.clock = clock;
    }

    /** One pass. */
    public void run() {
        store.queued(BATCH).forEach(this::make);
        store.expiring(clock.instant(), BATCH).forEach(this::remove);
    }

    private void make(ExportStore.Queued queued) {
        String key = queued.id().toString();
        try {
            byte[] file = switch (queued.format()) {
                case XLSX -> ManagementSummaryXlsx.render(queued.summary());
            };
            files.put(FileStore.Zone.EXPORT, key, file);
        } catch (RuntimeException e) {
            store.failed(queued.id());
            log.atWarn().setMessage("An export could not be made").addKeyValue("event.action", "export-failed")
                    .addKeyValue("labels.export_id", queued.id().toString()).log();
            return;
        }
        Instant now = clock.instant();
        store.ready(queued.id(), key, now, now.plus(KEPT_FOR));
        log.atInfo().setMessage("An export was made").addKeyValue("event.action", "export-ready")
                .addKeyValue("labels.export_id", queued.id().toString()).log();
    }

    private void remove(ExportStore.Expiring expiring) {
        try {
            files.delete(FileStore.Zone.EXPORT, expiring.fileKey());
        } catch (RuntimeException e) {
            log.atWarn().setMessage("An expired export's file could not be removed")
                    .addKeyValue("event.action", "export-remove-failed")
                    .addKeyValue("labels.export_id", expiring.id().toString()).log();
            return;
        }
        store.expired(expiring.id());
    }
}
