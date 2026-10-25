package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 47 (ticket 42): an Exemption resting on a scheduled VA stays conditional until the SMO/Tech Lead records that the
 * VA happened and a VA POC confirms it, after a fresh sign-in. Q3 of VA Cycle 2027 ends on Sunday 31 October 2027, so its
 * scheduled VA falls from 1 November 2027 to 31 January 2028, the day the Exemption lapses by.
 */
class ConditionalExemptionTest extends ExemptionSeam1 {

    /** SMO_A's SYS-F1 Draft, Exempted on a VA scheduled for 15 November 2027; the Exemption's id. */
    private String conditionallyExempted() {
        Draft draft = draft();
        String id = requested(draft.smo(), draft.systemId(), scheduledVa()).get("id").asString();
        approved(vaPoc(), id);
        return id;
    }

    @Test
    void theSmoRecordsThatTheScheduledVaHappened() {
        String id = conditionallyExempted();
        String system = idOf("SYS-F1");
        today("2027-11-20");
        ApiClient smo = signedInAs(SMO_A);

        JsonNode answered = recordedVa(smo, id, vaHappened("2027-11-16", "Acme report AC-2027-11 received."));

        assertThat(answered.get("vaHappenedOn").asString()).isEqualTo("2027-11-16");
        assertThat(answered.get("vaEvidence").asString()).isEqualTo("Acme report AC-2027-11 received.");
        assertThat(answered.get("vaRecordedBy").asString()).isEqualTo(userId(smo));
        assertThat(answered.get("vaRecordedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(answered.get("conditional").asBoolean()).as("until a VA POC confirms it").isTrue();
        assertThat(answered.get("confirmedAt").isNull()).isTrue();
        JsonNode audited = entries("EXEMPTION_VA_RECORDED", system).get(0);
        assertThat(audited.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get("detail").get("exemption_id").asString()).isEqualTo(id);
        assertThat(audited.toString()).as("the evidence a person typed is never audited").doesNotContain("Acme");
        assertThat(submission(smo, answered.get("submissionId").asString()).get("state").asString()).isEqualTo(EXEMPTED);
        for (ApiClient poc : List.of(vaPoc(), otherVaPoc())) {
            JsonNode item = only(poc, "CONFIRM_VA");
            assertThat(item.get("state").asString()).isEqualTo(EXEMPTED);
            assertThat(item.get("link").asString()).isEqualTo("/va-poc/exemptions");
            assertThat(item.toString()).as("a notification carries no evidence").doesNotContain("Acme");
        }
    }

    @Test
    void aVaPocConfirmsTheRecordedVaAfterAFreshSignInAndTheExemptionIsNoLongerConditional() {
        String id = conditionallyExempted();
        String system = idOf("SYS-F1");
        // The step-up is asked before anything is read; asked on the default day, as its REQUEST_REFUSED entry is read
        // newest first by other tests.
        ApiClient poc = otherVaPoc();
        clock.advance(Duration.ofMinutes(6));
        var stale = confirmVa(poc, id);
        assertThat(stale.status()).isEqualTo(403);
        assertThat(ApiClient.json(stale).get("stepUpPath").asString()).isEqualTo("/auth/step-up?intent=" + DECIDE_INTENT);
        today("2027-11-20");
        ApiClient smo = signedInAs(SMO_A);
        recordedVa(smo, id, vaHappened("2027-11-16", "Acme report AC-2027-11 received."));
        poc = otherVaPoc();

        JsonNode answered = confirmedVa(poc, id);

        assertThat(answered.get("conditional").asBoolean()).isFalse();
        assertThat(answered.get("confirmedBy").asString()).isEqualTo(userId(poc));
        assertThat(answered.get("confirmedAt").asString()).isEqualTo(clock.instant().toString());
        JsonNode audited = entries("EXEMPTION_VA_CONFIRMED", system).get(0);
        assertThat(audited.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get("detail").get("exemption_id").asString()).isEqualTo(id);
        assertThat(only(vaPoc(), "CONFIRM_VA").get("handledBy").isNull()).as("cleared for both").isFalse();
        JsonNode told = only(signedInAs(SMO_A), "VA_CONFIRMED");
        assertThat(told.get("state").asString()).isEqualTo(EXEMPTED);
        assertThat(told.get("link").asString()).isEqualTo("/smo/submissions/" + answered.get("submissionId").asString());
        assertConflict(confirmVa(otherVaPoc(), id), "invalid-transition");
        assertThat(entries("EXEMPTION_VA_CONFIRMED", system).get(0).get("detail").get("reason").asString())
                .isEqualTo("already-confirmed");
    }

    @Test
    void theVaPocsQueueListsRecordedVasToConfirmFromEveryCycleUntilOneIsConfirmed() {
        registered(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-Q4", "Fourth", SMO_A, CM, "Q4")),
                List.of());
        ApiClient smo = signedInAs(SMO_A);
        String q3 = requested(smo, idOf("SYS-F1"), scheduledVa()).get("id").asString();
        String q4 = requested(smo, idOf("SYS-Q4"),
                exemption(SCHEDULED_VA, "2028-03-20", "Third-party VA booked with Acme, PO 4472.", null)).get("id")
                .asString();
        approved(vaPoc(), q3);
        approved(vaPoc(), q4);
        today("2027-11-20");
        recordedVa(signedInAs(SMO_A), q3, vaHappened("2027-11-16", "Acme report AC-2027-11 received."));
        assertThat(confirmations(vaPoc())).extracting(item -> item.get("exemptionId").asString()).containsExactly(q3);
        today("2028-03-06");
        arrange.cycleLifecycle();
        today("2028-03-25");
        recordedVa(signedInAs(SMO_A), q4, vaHappened("2028-03-21", "Acme report AC-2028-03 received."));

        JsonNode waiting = confirmations(vaPoc());

        assertThat(waiting).extracting(item -> item.get("exemptionId").asString()).containsExactly(q3, q4);
        JsonNode item = waiting.get(1);
        assertThat(item.get("systemCode").asString()).isEqualTo("SYS-Q4");
        assertThat(item.get("cycle").asInt()).as("its VA Cycle has closed").isEqualTo(2027);
        assertThat(item.get("quarter").asInt()).isEqualTo(4);
        assertThat(item.get("vaDate").asString()).isEqualTo("2028-03-20");
        assertThat(item.get("vaHappenedOn").asString()).isEqualTo("2028-03-21");
        assertThat(item.get("vaEvidence").asString()).isEqualTo("Acme report AC-2028-03 received.");
        assertThat(item.get("sodConflict").asBoolean()).isFalse();
        confirmedVa(otherVaPoc(), q3);
        assertThat(confirmations(vaPoc())).extracting(next -> next.get("exemptionId").asString()).containsExactly(q4);
    }

    @Test
    void aVaPocTheRegisterNamesAsTheSystemsSmoLeavesTheConfirmationToTheOtherVaPoc() {
        registered(List.of(listed("SYS-F1", "File one", POC_ONE, CM, "Q3")), List.of());
        String system = idOf("SYS-F1");
        String id = requested(vaPoc(), system, scheduledVa()).get("id").asString();
        approved(otherVaPoc(), id);
        today("2027-11-20");
        recordedVa(vaPoc(), id, vaHappened("2027-11-16", "Acme report AC-2027-11 received."));

        assertThat(confirmations(vaPoc()).get(0).get("sodConflict").asBoolean()).isTrue();
        assertSodConflict(confirmVa(vaPoc(), id));

        assertThat(entries("EXEMPTION_VA_CONFIRMED", system).get(0).get("detail").get("reason").asString())
                .isEqualTo("sod-conflict");
        assertThat(confirmedVa(otherVaPoc(), id).get("conditional").asBoolean()).isFalse();
    }

    @Test
    void onlyAConditionalExemptionsSmoRecordsItsVaOnceByTheLapseDateAndOnlyAVaPocConfirmsARecordedOne() {
        Draft draft = draft();
        String prior = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();
        approved(vaPoc(), prior);
        ApiClient smoB = signedInAs(SMO_B);
        String other = idOf("SYS-F2");
        String id = requested(smoB, other, scheduledVa()).get("id").asString();
        approved(vaPoc(), id);
        String happened = vaHappened("2027-11-16", "Acme report AC-2027-11 received.");
        // The role refusals come first, on the default day: each is a REQUEST_REFUSED entry, which other tests read
        // newest first, so none is left at a later day.
        assertThat(recordVa(signedInAs(CM), id, happened).status()).as("not an SMO/Tech Lead").isEqualTo(403);
        assertThat(confirmVa(smoB, id).status()).as("not a VA POC").isEqualTo(403);
        today("2027-11-20");
        ApiClient smoA = signedInAs(SMO_A);
        smoB = signedInAs(SMO_B);

        assertConflict(recordVa(smoA, prior, happened), "invalid-transition");
        assertNotFound(recordVa(smoA, id, happened));
        assertNotFound(recordVa(smoB, UUID.randomUUID().toString(), happened));
        assertConflict(confirmVa(vaPoc(), id), "invalid-transition");
        recordedVa(smoB, id, happened);
        assertConflict(recordVa(smoB, id, happened), "invalid-transition");

        assertThat(entries("EXEMPTION_VA_RECORDED", draft.systemId()).get(0).get("detail").get("reason").asString())
                .isEqualTo("not-conditional");
        assertThat(entries("EXEMPTION_VA_RECORDED", other).get(0).get("detail").get("reason").asString())
                .isEqualTo("already-recorded");
        assertThat(entries("EXEMPTION_VA_CONFIRMED", other).get(0).get("detail").get("reason").asString())
                .isEqualTo("va-not-recorded");
    }

    @Test
    void theVaCannotBeRecordedAfterTheLapseDate() {
        String id = conditionallyExempted();
        today("2028-02-01");

        assertConflict(recordVa(signedInAs(SMO_A), id, vaHappened("2028-01-30", "Acme report AC-2028-01.")),
                "invalid-transition");

        assertThat(entries("EXEMPTION_VA_RECORDED", idOf("SYS-F1")).get(0).get("detail").get("reason").asString())
                .isEqualTo("exemption-lapsed");
    }

    @Test
    void theVaHappenedFormIsCheckedFieldByField() {
        String id = conditionallyExempted();
        today("2027-11-20");
        ApiClient smo = signedInAs(SMO_A);

        assertInvalidField(recordVa(smo, id, vaHappened(null, "Acme.")), "vaDate");
        assertInvalidField(recordVa(smo, id, vaHappened("16/11/2027", "Acme.")), "vaDate");
        assertInvalidField(recordVa(smo, id, vaHappened("2027-10-31", "Acme.")), "vaDate");
        assertInvalidField(recordVa(smo, id, vaHappened("2027-11-21", "Acme.")), "vaDate");
        assertInvalidField(recordVa(smo, id, vaHappened("2027-11-16", null)), "evidence");
        assertInvalidField(recordVa(smo, id, vaHappened("2027-11-16", " ​ ")), "evidence");
        assertInvalidField(recordVa(smo, id, vaHappened("2027-11-16", "a".repeat(4001))), "evidence");

        assertThat(recordedVa(smo, id, vaHappened("2027-11-20", "a".repeat(4000))).get("vaHappenedOn").asString())
                .isEqualTo("2027-11-20");
    }
}
