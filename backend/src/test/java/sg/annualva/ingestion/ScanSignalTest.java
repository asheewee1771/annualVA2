package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.annualva.harness.Metrics.SCAN_FAILURES;
import static sg.annualva.harness.Metrics.SCAN_TIMEOUTS;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.Samples;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): a scanner or store call that fails is counted as a failure at each call, an upload the
 * store cannot quarantine included, and a file given up on after its third attempt is counted as a timeout; a file the
 * scanner passes, or blocks, is neither.
 */
class ScanSignalTest extends IngestionSeam1 {

    private static final Duration PAST_THE_TIMEOUT = Duration.ofMinutes(3);

    @Test
    void aFileTheScannerBlocksIsNeitherAFailureNorATimeout() {
        Draft draft = draft();
        Metrics before = metrics();

        settled(draft, DEPENDENCY_CHECK, "report_quarantined.xml", Samples.dependencyCheckXml());

        Metrics after = metrics();
        assertThat(after.since(before, SCAN_FAILURES)).isZero();
        assertThat(after.since(before, SCAN_TIMEOUTS)).isZero();
    }

    @Test
    void anUploadTheStoreCannotQuarantineIsOneFailureAndNoTimeout() {
        Draft draft = draft();
        fileStore.refuseWrites(true);
        Metrics before = metrics();

        var response = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "dc.xml",
                Samples.dependencyCheckXml());

        assertThat(response.status()).isEqualTo(503);
        Metrics after = metrics();
        assertThat(after.since(before, SCAN_FAILURES)).isEqualTo(1);
        assertThat(after.since(before, SCAN_TIMEOUTS)).as("no scan attempt was made").isZero();
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

        assertThat(first.since(before, SCAN_FAILURES)).isEqualTo(1);
        assertThat(first.since(before, SCAN_TIMEOUTS)).as("one attempt is not a timeout").isZero();
        assertThat(last.since(before, SCAN_FAILURES)).as("three submissions failed").isEqualTo(3);
        assertThat(last.since(before, SCAN_TIMEOUTS)).isEqualTo(1);
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
        assertThat(after.since(before, SCAN_TIMEOUTS)).isEqualTo(1);
        assertThat(after.since(before, SCAN_FAILURES)).as("the scanner answered; it only never finished").isZero();
    }
}
