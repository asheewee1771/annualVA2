package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.annualva.harness.Metrics.MAIL_ABANDONED;
import static sg.annualva.harness.Metrics.MAIL_ADDRESS_REJECTED;
import static sg.annualva.harness.Metrics.MAIL_REFUSED;
import static sg.annualva.harness.Metrics.MAIL_SEND_FAILED;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): every mail the relay does not take is counted by what went wrong, and a mail that goes
 * out is not counted.
 */
class MailSignalTest extends MailSeam1 {

    @Test
    void aMailTheRelayTakesIsNotAFailure() {
        returnedForRework();
        Metrics before = metrics();

        dispatched();

        assertThat(mailTo(SMO_A)).hasSize(1);
        Metrics after = metrics();
        for (String kind : Metrics.MAIL_FAILURES) {
            assertThat(after.since(before, kind)).as(kind).isZero();
        }
    }

    @Test
    void eachFailedHandOverIsCountedAndAMailGivenUpOnAfterItsFifthAttemptIsCountedOnceMore() {
        returnedForRework();
        mailRelay.failNext(100);
        Metrics before = metrics();

        for (long wait : new long[] {1, 2, 4, 8}) {
            dispatched();
            clock.advance(Duration.ofMinutes(wait));
        }
        Metrics beforeTheLast = metrics();
        dispatched();
        Metrics after = metrics();

        assertThat(beforeTheLast.since(before, MAIL_SEND_FAILED)).isEqualTo(4);
        assertThat(beforeTheLast.since(before, MAIL_ABANDONED)).isZero();
        assertThat(after.since(before, MAIL_SEND_FAILED)).as("five failed attempts").isEqualTo(5);
        assertThat(after.since(before, MAIL_ABANDONED)).isEqualTo(1);
    }

    @Test
    void anAddressTheRelayRefusesAndAValueTheAllowListRefusesAreCountedApart() {
        Draft draft = draft();
        today("2027-08-16");
        arrange.overdueSweep();
        mailRelay.rejectAddress(SMO_B);
        arrange.renameSystem(draft.systemId(), "File one \u0007");
        Metrics before = metrics();

        dispatched();

        Metrics after = metrics();
        assertThat(after.since(before, MAIL_ADDRESS_REJECTED)).isEqualTo(1);
        assertThat(after.since(before, MAIL_REFUSED)).isPositive();
        assertThat(after.since(before, MAIL_SEND_FAILED)).as("neither is the relay failing").isZero();
    }
}
