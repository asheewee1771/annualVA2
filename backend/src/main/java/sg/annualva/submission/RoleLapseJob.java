package sg.annualva.submission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link RoleLapses#run} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class RoleLapseJob {

    private final RoleLapses lapses;
    private final KeySignals signals;

    RoleLapseJob(RoleLapses lapses, KeySignals signals) {
        this.lapses = lapses;
        this.signals = signals;
    }

    /**
     * The ticket's daily job, run hourly between passes and once at startup, as the Overdue sweep is (ADR 0033 item 6): a
     * Quarter begins on a whole business day, so its returns land within the hour after midnight in Singapore, and a pass
     * that finds nothing to lapse or return changes nothing, and is recorded as completed like any pass that returns
     * (ticket 67).
     */
    @Scheduled(fixedDelayString = "${annualva.roles.lapse-interval:1h}")
    void run() {
        lapses.run();
        signals.jobCompleted(Job.ROLE_LAPSE);
    }
}
