package sg.annualva.submission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link OverdueSweep} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class OverdueSweepJob {

    private final OverdueSweep sweep;
    private final KeySignals signals;

    OverdueSweepJob(OverdueSweep sweep, KeySignals signals) {
        this.sweep = sweep;
        this.signals = signals;
    }

    /**
     * Hourly, between passes rather than at a fixed rate, and once at startup, so a window that passed while the
     * application was down is flagged as soon as it is back. The windows are whole business days, so an hour is soon
     * enough. A pass that commits is recorded as completed (ticket 67).
     */
    @Scheduled(fixedDelayString = "${annualva.overdue.sweep-interval:1h}")
    void run() {
        sweep.sweep();
        signals.jobCompleted(Job.OVERDUE_SWEEP);
    }
}
