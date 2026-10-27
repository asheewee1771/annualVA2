package sg.annualva.adapters.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.boundary.MailRelay.Accepted;
import sg.annualva.boundary.MailRelay.AddressRejected;

/** Ticket 63: the local mail relay logs one {@code Event=email} line per message and never an address or free text. */
@ExtendWith(OutputCaptureExtension.class)
class LocalMailRelayAndStatusTest {

    private static final Map<String, String> VALUES = Map.of("system_id", "SYS-1", "system_name", "Payroll",
            "state", "Draft", "action", "Prepare and submit", "due_date", "16 August 2027", "link", "https://localhost:8443/smo",
            "free_text", "SECRET-FREE-TEXT", "recipient_name", "SECRET-NAME");

    private final LocalMailRelay relay = new LocalMailRelay();

    @Test
    void acceptsAMessageWithAnIdOfItsOwn() {
        var first = relay.send("smo.a@dev.annualva.test", "dev-overdue", VALUES);
        var second = relay.send("smo.a@dev.annualva.test", "dev-overdue", VALUES);

        assertThat(first).isInstanceOf(Accepted.class);
        assertThat(((Accepted) first).messageId()).isNotBlank().isNotEqualTo(((Accepted) second).messageId());
    }

    @Test
    void logsOneEventEmailLineWithTheSystemStateActionDueDateAndLink(CapturedOutput output) {
        relay.send("smo.a@dev.annualva.test", "dev-overdue", VALUES);

        List<String> lines = output.getAll().lines().filter(line -> line.contains("Event=email")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("SYS-1").contains("Payroll").contains("Draft").contains("Prepare and submit")
                .contains("16 August 2027").contains("https://localhost:8443/smo").contains("dev-overdue");
    }

    @Test
    void neverLogsTheAddressOrAValueThatIsNotOnTheAllowList(CapturedOutput output) {
        relay.send("smo.a@dev.annualva.test", "dev-overdue", VALUES);
        relay.send("someone.bounce@dev.annualva.test", "dev-overdue", VALUES);

        assertThat(output.getAll()).doesNotContain("smo.a").doesNotContain("someone").doesNotContain("dev.annualva.test")
                .doesNotContain("SECRET-FREE-TEXT").doesNotContain("SECRET-NAME");
    }

    @Test
    void anAddressWhoseLocalPartEndsInDotBounceIsRejected() {
        assertThat(relay.send("smo.a.bounce@dev.annualva.test", "dev-overdue", VALUES)).isInstanceOf(AddressRejected.class);
        assertThat(relay.send("SMO.A.BOUNCE@dev.annualva.test", "dev-overdue", VALUES)).isInstanceOf(AddressRejected.class);
    }

    @Test
    void aRejectedMessageIsNotLoggedAsSent(CapturedOutput output) {
        relay.send("x.bounce@dev.annualva.test", "dev-overdue", VALUES);

        assertThat(output.getAll()).doesNotContain("Event=email");
    }

    @Test
    void onlyTheLocalPartCountsAndOnlyAtItsEnd() {
        assertThat(relay.send("bounce@dev.annualva.test", "t", VALUES)).isInstanceOf(Accepted.class);
        assertThat(relay.send("a.bounce.b@dev.annualva.test", "t", VALUES)).isInstanceOf(Accepted.class);
        assertThat(relay.send("a@x.bounce", "t", VALUES)).isInstanceOf(Accepted.class);
    }

    @Test
    void theIdentityStatusReportsEveryoneEnabled() {
        var status = new LocalIdentityStatus();

        assertThat(status.disabled("11111111-0000-4000-8000-000000000001")).isFalse();
        assertThat(status.disabled("anyone")).isFalse();
    }
}
