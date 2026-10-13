package sg.annualva.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ScanPoller} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests leave
 * it off, move the clock and call the poller themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class ScanPollerJob {

    private final ScanPoller poller;

    ScanPollerJob(ScanPoller poller) {
        this.poller = poller;
    }

    /** Between passes, not at a fixed rate: a slow pass never overlaps the next. The default is ScanProperties.pollInterval. */
    @Scheduled(fixedDelayString = "${annualva.scan.poll-interval:10s}")
    void run() {
        poller.poll();
    }
}
