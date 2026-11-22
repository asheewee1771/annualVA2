package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.annualva.harness.Metrics.SCAN_FAILURES;
import static sg.annualva.harness.Metrics.SCAN_TIMEOUTS;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.FakeMalwareScanner.Fate;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.RegisterXlsx;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): the System Register workbook's synchronous scan counts into the same signals as a Scan
 * Report file's: each failed scanner or store call is a failure, a workbook the store cannot quarantine is one failure
 * and no timeout, and a workbook refused after its third attempt is a timeout.
 */
class RegisterScanSignalTest extends RegisterSeam1 {

    private static byte[] workbook(String systemId) {
        return RegisterXlsx.register()
                .row(systemId, "Scanned", "signal.smo@ci.annualva.test", "signal.cm@ci.annualva.test", "Q1").build();
    }

    @Test
    void aWorkbookWhoseScanErrorsThenPassesCountsTheErrorsAndNoTimeout() {
        scanner.nextFates(Fate.ERROR, Fate.ERROR, Fate.CLEAN);
        Metrics before = metrics();

        var response = upload(vaPoc(), workbook("SYS-SG-1"));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        Metrics after = metrics();
        assertThat(after.since(before, SCAN_FAILURES)).isEqualTo(2);
        assertThat(after.since(before, SCAN_TIMEOUTS)).isZero();
    }

    @Test
    void aWorkbookWhoseScannerCannotBeReachedIsCountedAsThreeFailuresAndOneTimeout() {
        scanner.failNextSubmissions(3);
        Metrics before = metrics();

        var response = upload(vaPoc(), workbook("SYS-SG-2"));

        assertThat(response.status()).isEqualTo(503);
        Metrics after = metrics();
        assertThat(after.since(before, SCAN_FAILURES)).isEqualTo(3);
        assertThat(after.since(before, SCAN_TIMEOUTS)).isEqualTo(1);
    }

    @Test
    void aWorkbookTheStoreCannotQuarantineIsOneFailureAndNoTimeout() {
        fileStore.refuseWrites(true);
        Metrics before = metrics();

        var response = upload(vaPoc(), workbook("SYS-SG-4"));

        assertThat(response.status()).isEqualTo(503);
        Metrics after = metrics();
        assertThat(after.since(before, SCAN_FAILURES)).isEqualTo(1);
        assertThat(after.since(before, SCAN_TIMEOUTS)).as("no scan attempt was made").isZero();
    }

    @Test
    void aWorkbookThatNeverFinishesScanningIsOneTimeoutAndNoFailure() {
        scanner.nextFates(Fate.NEVER_FINISHES, Fate.NEVER_FINISHES, Fate.NEVER_FINISHES);
        Metrics before = metrics();

        var response = upload(vaPoc(), workbook("SYS-SG-3"));

        assertThat(response.status()).isEqualTo(503);
        Metrics after = metrics();
        assertThat(after.since(before, SCAN_FAILURES)).isZero();
        assertThat(after.since(before, SCAN_TIMEOUTS)).isEqualTo(1);
    }
}
