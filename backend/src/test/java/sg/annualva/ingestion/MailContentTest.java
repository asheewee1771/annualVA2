package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.CapturingMailRelay.Sent;

/**
 * Story 91 for mail (ticket 37): a forwarded email leaks nothing. A sentinel is put into every place a person or a report
 * can write text that a mail might copy (a Finding entered by hand, a Dispute and a return comment), the Findings of a
 * parsed report stand for the rest, and the workflow is driven through submit, return and the Overdue sweep; then no
 * captured mail holds any of it. And no recipient address reaches the logs, whatever the relay answers.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailContentTest extends MailSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";

    @Test
    void noMailCarriesAFindingAVulnerabilityOrAComment() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, SENTINEL + " weak cipher", SENTINEL + "-ID",
                SENTINEL + "-rule", "HIGH"));
        proposeEvery(draft);
        submitted(draft.smo(), sub);
        ApiClient cm = signedInAs(CM);
        List<String> detail = findingDetail(cm, sub);
        reviewed(cm, sub, findingIds(cm, sub).get(0), DISPUTED, SENTINEL + " in the dispute");
        returned(cm, sub, SENTINEL + " in the return");
        today("2027-08-30");
        arrange.overdueSweep();

        dispatched();

        assertThat(mailTo(SMO_A)).extracting(Sent::templateId).contains(REWORK_TEMPLATE, OVERDUE_TEMPLATE);
        assertThat(detail).as("the report's Findings and the one entered by hand").hasSizeGreaterThan(4)
                .anyMatch(text -> text.contains(SENTINEL));
        for (Sent mail : mailRelay.sent()) {
            String everything = mail.templateId() + " " + mail.values();
            assertThat(everything).doesNotContain("SENTINEL");
            detail.forEach(text -> assertThat(everything).doesNotContain(text));
        }
    }

    @Test
    void noRecipientAddressReachesTheLogsWhenTheRelayFailsOrRefusesAMailOrTheAllowListDoes(CapturedOutput output) {
        Draft draft = draft();
        today("2027-08-16");
        arrange.overdueSweep();
        mailRelay.failNext(2);
        dispatched();
        mailRelay.rejectAddress(SMO_B);
        arrange.renameSystem(draft.systemId(), "File one \u0007");
        clock.advance(Duration.ofMinutes(1));

        dispatched();

        assertThat(outboxOf(SMO_A).getFirst().get("status").asString()).isEqualTo("REFUSED");
        assertThat(outboxOf(SMO_B).getFirst().get("status").asString()).isEqualTo("REJECTED");
        assertThat(output.getAll()).contains("mail-send-failed").contains("mail-address-rejected")
                .contains("mail-refused");
        for (String address : List.of(SMO_A, SMO_B, CM, CM_OTHER)) {
            assertThat(output.getAll()).doesNotContainIgnoringCase(address);
        }
    }
}
