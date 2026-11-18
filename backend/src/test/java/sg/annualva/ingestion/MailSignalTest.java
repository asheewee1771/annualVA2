package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): every mail the relay does not take is counted by what went wrong, and a mail that goes
 * out is not counted.
 */
class MailSignalTest extends MailSeam1 {

    private static final String SEND_FAILED = "annualva_mail_failures_total{kind=\"send_failed\"}";
    private static final String ABANDONED = "annualva_mail_failures_total{kind=\"abandoned\"}";
    private static final String REFUSED = "annualva_mail_failures_total{kind=\"refused\"}";
    private static final String ADDRESS_REJECTED = "annualva_mail_failures_total{kind=\"address_rejected\"}";

    @Test
    void aMailTheRelayTakesIsNotAFailure() {
        returnedForRework();
        Metrics before = metrics();

        dispatched();

        assertThat(mailTo(SMO_A)).hasSize(1);
        Metrics after = metrics();
        for (String kind : new String[] {SEND_FAILED, ABANDONED, REFUSED, ADDRESS_REJECTED}) {
            assertThat(after.value(kind)).as(kind).isEqualTo(before.value(kind));
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

        assertThat(beforeTheLast.value(SEND_FAILED)).isEqualTo(before.value(SEND_FAILED) + 4);
        assertThat(beforeTheLast.value(ABANDONED)).isEqualTo(before.value(ABANDONED));
        assertThat(after.value(SEND_FAILED)).as("five failed attempts").isEqualTo(before.value(SEND_FAILED) + 5);
        assertThat(after.value(ABANDONED)).isEqualTo(before.value(ABANDONED) + 1);
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
        assertThat(after.value(ADDRESS_REJECTED)).isEqualTo(before.value(ADDRESS_REJECTED) + 1);
        assertThat(after.value(REFUSED)).isGreaterThan(before.value(REFUSED));
        assertThat(after.value(SEND_FAILED)).as("neither is the relay failing").isEqualTo(before.value(SEND_FAILED));
    }
}
