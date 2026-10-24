package sg.annualva.submission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link CycleLifecycle#run} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests
 * leave it off, move the clock and run a pass themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class CycleLifecycleJob {

    private final CycleLifecycle lifecycle;

    CycleLifecycleJob(CycleLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Hourly, between passes, and once at startup, so a Cycle whose Q1 started or whose close date passed while the
     * application was down is opened or closed as soon as it is back. Opening and closing fall on whole business days, so
     * each lands within the hour after midnight in Singapore; a pass that finds nothing to do changes nothing.
     */
    @Scheduled(fixedDelayString = "${annualva.cycle.lifecycle-interval:1h}")
    void run() {
        lifecycle.run();
    }
}
