package sg.annualva.submission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link CycleLifecycle#run} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class CycleLifecycleJob {

    private final CycleLifecycle lifecycle;
    private final KeySignals signals;

    CycleLifecycleJob(CycleLifecycle lifecycle, KeySignals signals) {
        this.lifecycle = lifecycle;
        this.signals = signals;
    }

    /**
     * Hourly, between passes, and once at startup, so a Cycle whose Q1 started or whose close date passed while the
     * application was down is opened or closed as soon as it is back. Opening and closing fall on whole business days, so
     * each lands within the hour after midnight in Singapore; a pass that finds nothing to do changes nothing, and is
     * recorded as completed like any pass that returns (ticket 67).
     */
    @Scheduled(fixedDelayString = "${annualva.cycle.lifecycle-interval:1h}")
    void run() {
        lifecycle.run();
        signals.jobCompleted(Job.CYCLE_LIFECYCLE);
    }
}
