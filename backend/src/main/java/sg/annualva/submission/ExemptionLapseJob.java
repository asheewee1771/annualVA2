package sg.annualva.submission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link ExemptionLapse#run} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class ExemptionLapseJob {

    private final ExemptionLapse lapse;
    private final KeySignals signals;

    ExemptionLapseJob(ExemptionLapse lapse, KeySignals signals) {
        this.lapse = lapse;
        this.signals = signals;
    }

    /**
     * The ticket's daily job, run hourly between passes and once at startup, as the Overdue sweep is (ADR 0033 item 6): a
     * lapse falls on a whole business day, so it lands within the hour after midnight in Singapore, and a pass that finds
     * nothing to lapse changes nothing, and is recorded as completed like any pass that returns (ticket 67).
     */
    @Scheduled(fixedDelayString = "${annualva.exemption.lapse-interval:1h}")
    void run() {
        lapse.run();
        signals.jobCompleted(Job.EXEMPTION_LAPSE);
    }
}
