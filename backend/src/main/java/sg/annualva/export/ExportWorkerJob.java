package sg.annualva.export;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ExportWorker} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests leave
 * it off, move the clock and run the pass themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class ExportWorkerJob {

    private final ExportWorker worker;

    ExportWorkerJob(ExportWorker worker) {
        this.worker = worker;
    }

    /** Between passes, not at a fixed rate: a slow pass never overlaps the next. */
    @Scheduled(fixedDelayString = "${annualva.export.poll-interval:5s}")
    void run() {
        worker.run();
    }
}
