package sg.annualva.ingestion;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link ScanPoller} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class ScanPollerJob {

    private final ScanPoller poller;
    private final KeySignals signals;

    ScanPollerJob(ScanPoller poller, KeySignals signals) {
        this.poller = poller;
        this.signals = signals;
    }

    /**
     * Between passes, not at a fixed rate: a slow pass never overlaps the next. The default is ScanProperties.pollInterval.
     * Recorded as completed (ticket 67) unless the pass had files and every one failed ({@code file-pipeline-failed}, or a
     * leftover whose bytes the store refused to discard); a file the scanner failed on is an attempt, counted by a signal
     * of its own, and does not make the pass fail.
     */
    @Scheduled(fixedDelayString = "${annualva.scan.poll-interval:10s}")
    void run() {
        if (poller.poll()) {
            signals.jobCompleted(Job.SCAN_POLLER);
        }
    }
}
