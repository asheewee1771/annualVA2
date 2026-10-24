package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * ADR 0044 item 4 (ticket 37): a dispatcher that stops part-way (its instance dies while the relay has a mail) leaves the
 * mail leased, not lost. Another pass claims it again only once its five-minute lease has run out, so two instances never
 * hand the same mail over at once; a stopped last attempt is given up on without another hand-over.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailLeaseTest extends MailSeam1 {

    @Test
    void aMailWhoseDispatcherStoppedIsClaimedAgainOnlyOnceItsLeaseRunsOut() {
        returnedForRework();
        mailRelay.stopNext();

        arrange.mailDispatchStopped();
        JsonNode leased = onlyMailOf(SMO_A);
        assertThat(leased.get("status").asString()).isEqualTo("PENDING");
        assertThat(leased.get("attempts").asInt()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        dispatched();
        assertThat(onlyMailOf(SMO_A).get("attempts").asInt()).as("still leased").isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        dispatched();

        JsonNode sent = onlyMailOf(SMO_A);
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("attempts").asInt()).isEqualTo(2);
        assertThat(mailTo(SMO_A)).hasSize(1);
        assertThat(mailRelay.calls()).isEqualTo(2);
    }

    @Test
    void aHandOverThatOutlastsItsLeaseKeepsTheOutcomeOfTheClaimThatHoldsTheMail(CapturedOutput output) {
        returnedForRework();
        mailRelay.beforeNextAnswer(() -> {
            clock.advance(Duration.ofMinutes(6));
            dispatched();
        });

        dispatched();

        JsonNode sent = onlyMailOf(SMO_A);
        assertThat(mailTo(SMO_A)).as("delivery is at least once").hasSize(2);
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("attempts").asInt()).as("the second claim's outcome").isEqualTo(2);
        assertThat(sent.get("relayMessageId").asString()).isEqualTo(mailTo(SMO_A).getFirst().messageId());
        assertThat(output.getAll()).contains("mail-claim-lost").doesNotContainIgnoringCase(SMO_A);
    }

    @Test
    void aLastAttemptWhoseDispatcherStoppedIsGivenUpOnWithoutAnotherHandOver(CapturedOutput output) {
        returnedForRework();
        mailRelay.failNext(4);
        for (long wait : new long[] {1, 2, 4, 8}) {
            dispatched();
            clock.advance(Duration.ofMinutes(wait));
        }
        mailRelay.stopNext();
        arrange.mailDispatchStopped();
        assertThat(onlyMailOf(SMO_A).get("attempts").asInt()).isEqualTo(5);
        clock.advance(Duration.ofMinutes(5));

        dispatched();

        JsonNode failed = onlyMailOf(SMO_A);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(5);
        assertThat(mailRelay.calls()).as("no sixth hand-over").isEqualTo(5);
        assertThat(output.getAll()).contains("mail-abandoned").doesNotContainIgnoringCase(SMO_A);
    }
}
