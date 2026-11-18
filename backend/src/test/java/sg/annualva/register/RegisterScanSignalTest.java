package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.FakeMalwareScanner.Fate;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.RegisterXlsx;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): the System Register workbook's synchronous scan counts into the same signals as a Scan
 * Report file's: each failed scanner or store call is a failure, and a workbook refused after its third attempt is a
 * timeout.
 */
class RegisterScanSignalTest extends RegisterSeam1 {

    private static final String FAILURES = "annualva_scan_failures_total";
    private static final String TIMEOUTS = "annualva_scan_timeouts_total";

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
        assertThat(after.value(FAILURES)).isEqualTo(before.value(FAILURES) + 2);
        assertThat(after.value(TIMEOUTS)).isEqualTo(before.value(TIMEOUTS));
    }

    @Test
    void aWorkbookWhoseScannerCannotBeReachedIsCountedAsThreeFailuresAndOneTimeout() {
        scanner.failNextSubmissions(3);
        Metrics before = metrics();

        var response = upload(vaPoc(), workbook("SYS-SG-2"));

        assertThat(response.status()).isEqualTo(503);
        Metrics after = metrics();
        assertThat(after.value(FAILURES)).isEqualTo(before.value(FAILURES) + 3);
        assertThat(after.value(TIMEOUTS)).isEqualTo(before.value(TIMEOUTS) + 1);
    }

    @Test
    void aWorkbookThatNeverFinishesScanningIsOneTimeoutAndNoFailure() {
        scanner.nextFates(Fate.NEVER_FINISHES, Fate.NEVER_FINISHES, Fate.NEVER_FINISHES);
        Metrics before = metrics();

        var response = upload(vaPoc(), workbook("SYS-SG-3"));

        assertThat(response.status()).isEqualTo(503);
        Metrics after = metrics();
        assertThat(after.value(FAILURES)).isEqualTo(before.value(FAILURES));
        assertThat(after.value(TIMEOUTS)).isEqualTo(before.value(TIMEOUTS) + 1);
    }
}
