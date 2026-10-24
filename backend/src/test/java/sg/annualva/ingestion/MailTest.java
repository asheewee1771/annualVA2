package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.CapturingMailRelay.Sent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 91 and 94 (ticket 37): a required notification is queued for email when it is written, and the dispatcher sends
 * it through the mail relay with the template the relay knows it by and the allow-listed values only. Q3 of VA Cycle 2027:
 * the Submission Window ends on 15 August, the Review Window on 29 August and the Quarter on 31 October.
 */
class MailTest extends MailSeam1 {

    @Test
    void aReturnForReworkIsQueuedAndThenMailedToTheSmoWithTheSystemStateActionDueDateAndLinkOnly() {
        Draft draft = returnedForRework();
        String sub = draft.submissionId();

        assertThat(mailRelay.sent()).as("queued, not sent, until the dispatcher runs").isEmpty();
        JsonNode queued = outboxOf(SMO_A).getFirst();
        assertThat(queued.get("status").asString()).isEqualTo("PENDING");
        assertThat(queued.get("attempts").asInt()).isZero();

        dispatched();

        assertThat(mailTo(SMO_A)).hasSize(1);
        Sent mail = mailTo(SMO_A).getFirst();
        assertThat(mail.templateId()).isEqualTo(REWORK_TEMPLATE);
        assertThat(mail.values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "system_id", "SYS-F1",
                "system_name", "File one",
                "state", "Returned for Rework",
                "action", "Rework what is Disputed and submit the VA Submission again.",
                "due_date", "29 August 2027",
                "link", APP_URL + "/smo/submissions/" + sub + "/rework"));
        JsonNode sent = outboxOf(SMO_A).getFirst();
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("attempts").asInt()).isEqualTo(1);
        assertThat(sent.get("relayMessageId").asString()).isEqualTo(mail.messageId());
        assertThat(only(draft.smo(), "REWORK").get("state").asString()).as("the in-app item is the same notification")
                .isEqualTo(RETURNED_FOR_REWORK);
    }

    @Test
    void aMoveAndATransferByTheRegisterAreMailedToTheSmoAndTheFormerSmoWithWhatChanged() {
        registered(listed("SYS-N1", "Notice one", SMO_A, CM, "Q3"), listed("SYS-N2", "Notice two", SMO_A, CM, "Q3"));

        registered(listed("SYS-N1", "Notice one", SMO_B, CM, "Q3"), listed("SYS-N2", "Notice two", SMO_A, CM, "Q4"));
        dispatched();

        Sent incoming = mailTo(SMO_B).getFirst();
        assertThat(mailTo(SMO_B)).hasSize(1);
        assertThat(incoming.templateId()).isEqualTo(REGISTER_CHANGE_TEMPLATE);
        assertThat(incoming.values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "system_id", "SYS-N1",
                "system_name", "Notice one",
                "state", "Not Started",
                "action", "The System Register made you this System's SMO/Tech Lead.",
                "due_date", "15 August 2027",
                "link", APP_URL + "/smo",
                "change_kind", "Transferred to you"));
        assertThat(mailTo(SMO_A)).extracting(sent -> sent.values().get("change_kind"))
                .containsExactlyInAnyOrder("Transferred from you", "Moved to another Quarter");
        Sent outgoing = mailTo(SMO_A).stream()
                .filter(sent -> sent.values().get("change_kind").equals("Transferred from you")).findFirst().orElseThrow();
        assertThat(outgoing.values()).as("nothing is due from them any more").doesNotContainKey("due_date")
                .containsEntry("system_id", "SYS-N1").containsEntry("link", APP_URL + "/smo");
        Sent moved = mailTo(SMO_A).stream()
                .filter(sent -> sent.values().get("change_kind").equals("Moved to another Quarter")).findFirst()
                .orElseThrow();
        assertThat(moved.values()).containsEntry("system_id", "SYS-N2").containsEntry("due_date", "14 November 2027");
        assertThat(mailTo(CM)).as("the Cyber Manager is not told").isEmpty();
    }

    @Test
    void anOverdueIsMailedToWhoeverItsStepIsAndBothVaPocsGetTheirOwn() {
        Draft draft = awaitingEndorsement();

        today("2027-11-01");
        arrange.overdueSweep();
        dispatched();

        for (String poc : List.of(POC_ONE, POC_TWO)) {
            assertThat(mailTo(poc)).as(poc).hasSize(1);
            Sent mail = mailTo(poc).getFirst();
            assertThat(mail.templateId()).isEqualTo(OVERDUE_TEMPLATE);
            assertThat(mail.values()).containsEntry("state", "Awaiting Endorsement")
                    .containsEntry("action", "Overdue: endorse the VA Submission, or return it for rework.")
                    .containsEntry("due_date", "31 October 2027")
                    .containsEntry("link", APP_URL + "/va-poc/submissions/" + draft.submissionId())
                    .doesNotContainKey("change_kind");
        }
    }

    @Test
    void aSubmitAndAPassToEndorsementAreInAppOnly() {
        awaitingEndorsement();

        dispatched();

        assertThat(only(signedInAs(CM), "REVIEW")).isNotNull();
        assertThat(only(vaPoc(), "ENDORSE")).isNotNull();
        assertThat(arrange.mailOutbox()).as("neither is a required message").isEmpty();
        assertThat(mailRelay.calls()).isZero();
    }

    @Test
    void aFailedHandOverIsTriedAgainAfterAWaitThatDoublesAndTheRelaysMessageIdIsKept() {
        Draft draft = draft();
        // Two mails are due (SYS-F1's Draft and SYS-F2, Not Started): each fails twice, then goes out.
        mailRelay.failNext(4);
        today("2027-08-16");
        arrange.overdueSweep();

        dispatched();
        JsonNode first = outboxOf(SMO_A).getFirst();
        assertThat(first.get("status").asString()).isEqualTo("PENDING");
        assertThat(first.get("attempts").asInt()).isEqualTo(1);
        assertThat(first.get("nextAttemptAt").asString()).isEqualTo(clock.instant().plusSeconds(60).toString());
        clock.advance(Duration.ofSeconds(59));
        dispatched();
        assertThat(outboxOf(SMO_A).getFirst().get("attempts").asInt()).as("not due yet").isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        dispatched();
        JsonNode second = outboxOf(SMO_A).getFirst();
        assertThat(second.get("attempts").asInt()).isEqualTo(2);
        assertThat(second.get("nextAttemptAt").asString()).isEqualTo(clock.instant().plusSeconds(120).toString());
        clock.advance(Duration.ofMinutes(2));
        dispatched();

        JsonNode sent = outboxOf(SMO_A).getFirst();
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("attempts").asInt()).isEqualTo(3);
        assertThat(mailTo(SMO_A)).hasSize(1);
        assertThat(sent.get("relayMessageId").asString()).isEqualTo(mailTo(SMO_A).getFirst().messageId());
        assertThat(mailTo(SMO_A).getFirst().values()).containsEntry("link",
                APP_URL + "/smo/submissions/" + draft.submissionId());
    }

    @Test
    void aMailIsTriedAfterOneTwoFourAndEightMinutesAndGivenUpOnAfterItsFifthFailedAttempt() {
        returnedForRework();
        mailRelay.failNext(100);

        for (long wait : new long[] {1, 2, 4, 8}) {
            dispatched();
            String due = clock.instant().plus(Duration.ofMinutes(wait)).toString();
            assertThat(onlyMailOf(SMO_A).get("nextAttemptAt").asString()).as("after a failure, %d minutes", wait)
                    .isEqualTo(due);
            clock.advance(Duration.ofMinutes(wait).minusSeconds(1));
            dispatched();
            assertThat(onlyMailOf(SMO_A).get("nextAttemptAt").asString()).as("not tried before it is due")
                    .isEqualTo(due);
            clock.advance(Duration.ofSeconds(1));
        }
        dispatched();
        clock.advance(Duration.ofHours(1));
        dispatched();

        JsonNode failed = onlyMailOf(SMO_A);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(5);
        assertThat(mailRelay.calls()).as("five attempts, then never again").isEqualTo(5);
        assertThat(mailRelay.sent()).isEmpty();
    }

    @Test
    void aNotificationThatCannotBeWrittenQueuesNoMail() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).get(0), DISPUTED, "Show the upgrade.");
        arrange.notificationFault("on");

        var response = returnForRework(cm, sub, null);
        arrange.notificationFault("off");
        dispatched();

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(arrange.mailOutbox()).as("the mail is written with its notification or not at all").isEmpty();
        assertThat(mailRelay.calls()).isZero();
    }

    @Test
    void anAddressTheRelayRefusesIsSettledAndNotTriedAgainWhileOtherMailStillGoesOut() {
        draft();
        mailRelay.rejectAddress(SMO_A);
        today("2027-08-16");
        arrange.overdueSweep();

        dispatched();
        clock.advance(Duration.ofHours(1));
        dispatched();

        JsonNode rejected = outboxOf(SMO_A).getFirst();
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("attempts").asInt()).isEqualTo(1);
        assertThat(rejected.get("relayMessageId").isNull()).isTrue();
        assertThat(mailTo(SMO_A)).isEmpty();
        assertThat(mailTo(SMO_B)).hasSize(1);
        assertThat(only(signedInAs(SMO_A), "SUBMIT_OVERDUE")).as("the in-app item stays").isNotNull();
    }

    @Test
    void aValueOutsideTheAllowListIsNeverHandedToTheRelay() {
        Draft draft = draft();
        // A right-to-left override, which the Register refuses in a name, written by code point so the source stays plain.
        arrange.renameSystem(draft.systemId(), "File one " + (char) 0x202E + "gnp.exe");
        today("2027-08-16");
        arrange.overdueSweep();

        dispatched();

        JsonNode refused = outboxOf(SMO_A).getFirst();
        assertThat(refused.get("status").asString()).isEqualTo("REFUSED");
        assertThat(mailTo(SMO_A)).isEmpty();
        assertThat(mailTo(SMO_B)).as("another System's mail is unaffected").hasSize(1);
        assertThat(only(signedInAs(SMO_A), "SUBMIT_OVERDUE")).as("the in-app item stays").isNotNull();
    }

    @Test
    void aSystemIdOutsideTheAllowListIsNeverHandedToTheRelayEither() {
        draft();
        // A line break and a header after it, which the Register refuses in a System ID.
        arrange.recodeSystem(idOf("SYS-F2"), "SYS-F2" + (char) 0x0A + "Bcc: someone");
        today("2027-08-16");
        arrange.overdueSweep();

        dispatched();

        assertThat(onlyMailOf(SMO_B).get("status").asString()).isEqualTo("REFUSED");
        assertThat(mailTo(SMO_B)).isEmpty();
        assertThat(mailTo(SMO_A)).as("another System's mail is unaffected").hasSize(1);
    }
}
