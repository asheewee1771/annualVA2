package sg.annualva.submission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link RoleLapses#run} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests
 * leave it off, move the clock and run a pass themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class RoleLapseJob {

    private final RoleLapses lapses;

    RoleLapseJob(RoleLapses lapses) {
        this.lapses = lapses;
    }

    /**
     * The ticket's daily job, run hourly between passes and once at startup, as the Overdue sweep is (ADR 0033 item 6): a
     * Quarter begins on a whole business day, so its returns land within the hour after midnight in Singapore, and a pass
     * that finds nothing to lapse or return changes nothing.
     */
    @Scheduled(fixedDelayString = "${annualva.roles.lapse-interval:1h}")
    void run() {
        lapses.run();
    }
}
