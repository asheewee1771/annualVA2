package sg.annualva.submission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ExemptionLapse#run} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests
 * leave it off, move the clock and run a pass themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class ExemptionLapseJob {

    private final ExemptionLapse lapse;

    ExemptionLapseJob(ExemptionLapse lapse) {
        this.lapse = lapse;
    }

    /**
     * The ticket's daily job, run hourly between passes and once at startup, as the Overdue sweep is (ADR 0033 item 6): a
     * lapse falls on a whole business day, so it lands within the hour after midnight in Singapore, and a pass that finds
     * nothing to lapse changes nothing.
     */
    @Scheduled(fixedDelayString = "${annualva.exemption.lapse-interval:1h}")
    void run() {
        lapse.run();
    }
}
