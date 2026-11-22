package sg.annualva.export;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link ExportWorker} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class ExportWorkerJob {

    private final ExportWorker worker;
    private final KeySignals signals;

    ExportWorkerJob(ExportWorker worker, KeySignals signals) {
        this.worker = worker;
        this.signals = signals;
    }

    /**
     * Between passes, not at a fixed rate: a slow pass never overlaps the next. A pass is recorded as completed (ticket 67)
     * only when {@link ExportWorker#run} says it did its work: a pass in which every piece failed (a listing that failed,
     * {@code export-pass-failed}, or an export whose step threw, {@code export-step-failed}) is not, so it shows on the
     * job's series.
     */
    @Scheduled(fixedDelayString = "${annualva.export.poll-interval:5s}")
    void run() {
        if (worker.run()) {
            signals.jobCompleted(Job.EXPORT_WORKER);
        }
    }
}
