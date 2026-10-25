package sg.annualva.export;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.annualva.boundary.FileStore;

/**
 * The export job (ADR 0045): makes the files of queued exports and removes those whose 24 hours are up. A pass:
 * <ol>
 *   <li>makes the file of each of the oldest queued exports from what was read when it was asked for, keeps it in the
 *       file store's export zone (encrypted at rest) under the export's own id, and marks it ready for 24 hours; one that
 *       cannot be read back or made, or that the store refuses, is marked failed, and its requester asks again;
 *   <li>then, whatever the first step met, removes the file of every ready export whose 24 hours are up and marks it
 *       expired; one the store cannot remove stays ready, is no longer served, and is tried again on the next pass.
 * </ol>
 *
 * <p>Each export is handled on its own: whatever goes wrong with one is logged with its id and the kind of error and the
 * pass goes on to the next. No file store call is made inside a database transaction, and each move is guarded by the
 * status it moves from, so two passes that reach the same export (two instances) write the same bytes under the same key
 * and change its row once. Log lines carry export ids, outcomes and exception types only: never what an export holds and
 * never an exception's message, which could quote it (lm-19).
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
        each(() -> store.queued(BATCH), ExportStore.Queued::id, this::make);
        each(() -> store.expiring(clock.instant(), BATCH), ExportStore.Expiring::id, this::remove);
    }

    private void make(ExportStore.Queued queued) {
        byte[] file;
        try {
            ManagementSummary summary = store.summary(queued);
            file = switch (queued.format()) {
                case XLSX -> ManagementSummaryXlsx.render(summary);
                case PDF -> ManagementSummaryPdf.render(summary);
            };
            files.put(FileStore.Zone.EXPORT, key(queued.id()), file);
        } catch (RuntimeException e) {
            store.failed(queued.id());
            logFailure("export-failed", queued.id(), e);
            return;
        }
        Instant now = clock.instant();
        store.ready(queued.id(), key(queued.id()), now, now.plus(KEPT_FOR));
        log.atInfo().setMessage("An export was made").addKeyValue("event.action", "export-ready")
                .addKeyValue("labels.export_id", queued.id().toString()).log();
    }

    private void remove(ExportStore.Expiring expiring) {
        files.delete(FileStore.Zone.EXPORT, expiring.fileKey());
        store.expired(expiring.id());
    }

    /** Lists the work and handles each item on its own, so one failure, the listing's included, stops nothing else. */
    private static <T> void each(Supplier<List<T>> list, Function<T, UUID> id, Consumer<T> handle) {
        List<T> items;
        try {
            items = list.get();
        } catch (RuntimeException e) {
            logFailure("export-pass-failed", null, e);
            return;
        }
        for (T item : items) {
            try {
                handle.accept(item);
            } catch (RuntimeException e) {
                logFailure("export-step-failed", id.apply(item), e);
            }
        }
    }

    private static String key(UUID exportId) {
        return exportId.toString();
    }

    private static void logFailure(String action, @Nullable UUID exportId, RuntimeException e) {
        var line = log.atWarn().setMessage("An export step did not complete").addKeyValue("event.action", action)
                .addKeyValue("error.type", e.getClass().getName());
        if (exportId != null) {
            line = line.addKeyValue("labels.export_id", exportId.toString());
        }
        line.log();
    }
}
