package sg.annualva.submission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OverdueSweep} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests leave
 * it off, move the clock and run the sweep themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class OverdueSweepJob {

    private final OverdueSweep sweep;

    OverdueSweepJob(OverdueSweep sweep) {
        this.sweep = sweep;
    }

    /**
     * Hourly, between passes rather than at a fixed rate, and once at startup, so a window that passed while the
     * application was down is flagged as soon as it is back. The windows are whole business days, so an hour is soon
     * enough.
     */
    @Scheduled(fixedDelayString = "${annualva.overdue.sweep-interval:1h}")
    void run() {
        sweep.sweep();
    }
}
