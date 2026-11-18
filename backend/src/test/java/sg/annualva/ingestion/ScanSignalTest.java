package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.Samples;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): a scanner that cannot be reached is counted as a failure at each call, and a file
 * given up on after its third attempt is counted as a timeout; a file the scanner passes, or blocks, is neither.
 */
class ScanSignalTest extends IngestionSeam1 {

    private static final String FAILURES = "annualva_scan_failures_total";
    private static final String TIMEOUTS = "annualva_scan_timeouts_total";
    private static final Duration PAST_THE_TIMEOUT = Duration.ofMinutes(3);

    @Test
    void aFileTheScannerBlocksIsNeitherAFailureNorATimeout() {
        Draft draft = draft();
        Metrics before = metrics();

        settled(draft, DEPENDENCY_CHECK, "report_quarantined.xml", Samples.dependencyCheckXml());

        Metrics after = metrics();
        assertThat(after.value(FAILURES)).isEqualTo(before.value(FAILURES));
        assertThat(after.value(TIMEOUTS)).isEqualTo(before.value(TIMEOUTS));
    }

    @Test
    void aScannerThatCannotBeReachedIsCountedAtEachFailedCallAndTheFileGivenUpOnIsCountedOnceAsATimeout() {
        Draft draft = draft();
        scanner.failNextSubmissions(3);
        uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckXml());
        Metrics before = metrics();

        pass();
        Metrics first = metrics();
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        Metrics last = metrics();

        assertThat(first.value(FAILURES)).isEqualTo(before.value(FAILURES) + 1);
        assertThat(first.value(TIMEOUTS)).as("one attempt is not a timeout").isEqualTo(before.value(TIMEOUTS));
        assertThat(last.value(FAILURES)).as("three submissions failed").isEqualTo(before.value(FAILURES) + 3);
        assertThat(last.value(TIMEOUTS)).isEqualTo(before.value(TIMEOUTS) + 1);
    }

    @Test
    void aScanThatKeepsTimingOutIsCountedOnceAsATimeoutAndNotAsAFailure() {
        Draft draft = draft();
        uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "slow_never.xml", Samples.dependencyCheckXml());
        Metrics before = metrics();

        pass();
        for (int retry = 0; retry < 3; retry++) {
            clock.advance(PAST_THE_TIMEOUT);
            pass();
        }

        Metrics after = metrics();
        assertThat(after.value(TIMEOUTS)).isEqualTo(before.value(TIMEOUTS) + 1);
        assertThat(after.value(FAILURES)).as("the scanner answered; it only never finished").isEqualTo(before.value(FAILURES));
    }
}
