package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 47 (ticket 42): a conditional Exemption whose VA is not recorded within 3 months after its Quarter's last day
 * lapses. A daily job puts the VA Submission back where the request froze it, flagged as lapsed, with a Submission Window
 * of two weeks from the day it is flagged, a Review Window two weeks after that and the endorsement due by the later of
 * the Quarter's end and the VA Cycle's close; once the Cycle has closed it is Not Endorsed instead. Q3 of VA Cycle 2027
 * ends on 31 October 2027, so its conditional Exemptions lapse by 31 January 2028 and are flagged on 1 February 2028;
 * the Cycle closes at the end of 5 March 2028. Q4 ends on 6 February 2028, so its conditional Exemptions lapse by 6 May
 * 2028, after the close.
 */
class ExemptionLapseTest extends ExemptionSeam1 {

    private static final String LAPSED = "EXEMPTION_LAPSED";

    /** Requests a scheduled-VA Exemption for this System as its SMO/Tech Lead and has a VA POC approve it; its id. */
    private String conditionallyExempted(ApiClient smo, String system, String scheduled) {
        String id = requested(smo, system, scheduled).get("id").asString();
        approved(vaPoc(), id);
        return id;
    }

    @Test
    void aDraftWhoseVaWasNotRecordedLapsesBackToDraftWithNewWindowsTheDayAfterItsLapseDate() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String id = conditionallyExempted(draft.smo(), draft.systemId(), scheduledVa());
        today("2028-01-31");
        lapse();
        assertThat(submission(signedInAs(SMO_A), sub).get("state").asString()).as("not before").isEqualTo(EXEMPTED);
        today("2028-02-01");

        lapse();
        lapse();

        ApiClient smo = signedInAs(SMO_A);
        JsonNode back = submission(smo, sub);
        assertThat(back.get("state").asString()).isEqualTo("DRAFT");
        assertThat(back.get("submitDue").asString()).as("today plus 13 days").isEqualTo("2028-02-14");
        assertThat(back.get("reviewDue").asString()).isEqualTo("2028-02-28");
        assertThat(back.get("endorseDue").asString()).as("the Cycle's close, after the Quarter's end")
                .isEqualTo("2028-03-05");
        assertThat(back.get("overdue").asBoolean()).isFalse();
        JsonNode exemption = back.get("conditionalExemption");
        assertThat(exemption.get("id").asString()).isEqualTo(id);
        assertThat(exemption.get("lapsedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(exemption.get("conditional").asBoolean()).isFalse();

        JsonNode audited = entries(LAPSED, draft.systemId());
        assertThat(audited).as("once, however often the job runs").hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("actorId").isNull()).as("the job acts for nobody").isTrue();
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get(0).get("detail").get("state_from").asString()).isEqualTo(EXEMPTED);
        assertThat(audited.get(0).get("detail").get("state_to").asString()).isEqualTo("DRAFT");
        assertThat(audited.get(0).get("detail").get("exemption_id").asString()).isEqualTo(id);

        JsonNode told = only(smo, LAPSED);
        assertThat(told.get("state").asString()).isEqualTo("DRAFT");
        assertThat(told.get("dueOn").asString()).isEqualTo("2028-02-14");
        assertThat(told.get("link").asString()).isEqualTo("/smo/submissions/" + sub);

        JsonNode row = inboxRow(smo, "SYS-F1");
        assertThat(row.get("exemptionLapsed").asBoolean()).isTrue();
        assertThat(row.get("nextAction").asString()).isEqualTo("PREPARE_SUBMISSION");
        assertThat(row.get("nextActionDueOn").asString()).isEqualTo("2028-02-14");
        assertThat(matrixRow("SYS-F1").get("exemptionLapsed").asBoolean()).isTrue();
        assertThat(boardCard(signedInAs(CM), "SYS-F1").get("exemptionLapsed").asBoolean()).isTrue();
        assertThat(cmInboxItem(signedInAs(CM), "SYS-F1").get("exemptionLapsed").asBoolean()).isTrue();
        assertThat(matrixRow("SYS-F2").get("exemptionLapsed").asBoolean()).isFalse();

        today("2028-02-15");
        arrange.overdueSweep();
        assertThat(submission(signedInAs(SMO_A), sub).get("overdue").asBoolean()).as("its new window passed").isTrue();
    }

    @Test
    void aRecordedVaSuspendsTheLapseAndAVaPocStillConfirmsItAfterTheLapseDate() {
        Draft draft = draft();
        String id = conditionallyExempted(draft.smo(), draft.systemId(), scheduledVa());
        today("2027-11-20");
        recordedVa(signedInAs(SMO_A), id, vaHappened("2027-11-16", "Acme report AC-2027-11 received."));
        today("2028-02-01");

        lapse();

        assertThat(submission(signedInAs(SMO_A), draft.submissionId()).get("state").asString()).isEqualTo(EXEMPTED);
        assertThat(entries(LAPSED, draft.systemId())).isEmpty();
        assertThat(confirmedVa(otherVaPoc(), id).get("conditional").asBoolean()).isFalse();
    }

    @Test
    void aVaSubmissionTheRequestCreatedLapsesBackToNotStartedAndTheSmoStartsItWithTheNewWindows() {
        draft();
        ApiClient smoB = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        JsonNode request = requested(smoB, system, scheduledVa());
        String sub = request.get("submissionId").asString();
        approved(vaPoc(), request.get("id").asString());
        today("2028-02-01");

        lapse();

        smoB = signedInAs(SMO_B);
        JsonNode row = inboxRow(smoB, "SYS-F2");
        assertThat(row.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(row.get("nextAction").asString()).isEqualTo("START_SUBMISSION");
        assertThat(row.get("nextActionDueOn").asString()).isEqualTo("2028-02-14");
        assertThat(row.get("exemptionLapsed").asBoolean()).isTrue();
        JsonNode told = only(smoB, LAPSED);
        assertThat(told.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(told.get("link").asString()).isEqualTo("/smo");

        JsonNode started = started(smoB, system);
        assertThat(started.get("id").asString()).isEqualTo(sub);
        assertThat(started.get("state").asString()).isEqualTo("DRAFT");
        assertThat(started.get("submitDue").asString()).isEqualTo("2028-02-14");
    }

    @Test
    void aVaSubmissionInReviewLapsesBackToItsCyberManager() {
        Draft draft = inReview();
        conditionallyExempted(draft.smo(), draft.systemId(), scheduledVa());
        today("2028-02-01");

        lapse();

        ApiClient cm = signedInAs(CM);
        assertThat(submission(cm, draft.submissionId()).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(actions(cm)).as("asked to review again").containsExactly("REVIEW", "REVIEW");
        assertThat(only(signedInAs(SMO_A), LAPSED).get("state").asString()).isEqualTo("IN_REVIEW");
    }

    @Test
    void aQ4ConditionalExemptionLapsesAfterTheCycleHasClosedAndIsNotEndorsedWithNoNewWindows() {
        registered(List.of(listed("SYS-Q4", "Fourth", SMO_A, CM, "Q4")), List.of());
        String system = idOf("SYS-Q4");
        ApiClient smo = signedInAs(SMO_A);
        JsonNode request = requested(smo, system,
                exemption(SCHEDULED_VA, "2028-03-20", "Third-party VA booked with Acme, PO 4472.", null));
        String sub = request.get("submissionId").asString();
        approved(vaPoc(), request.get("id").asString());
        today("2028-03-06");
        arrange.cycleLifecycle();
        today("2028-05-06");
        lapse();
        assertThat(submission(signedInAs(SMO_A), sub).get("state").asString()).as("not before").isEqualTo(EXEMPTED);
        today("2028-05-07");

        lapse();

        smo = signedInAs(SMO_A);
        JsonNode after = submission(smo, sub);
        assertThat(after.get("state").asString()).isEqualTo("NOT_ENDORSED");
        assertThat(after.get("submitDue").asString()).as("no new windows").isEqualTo("2027-11-14");
        assertThat(after.get("endorseDue").asString()).isEqualTo("2028-02-06");
        JsonNode audited = entries(LAPSED, system).get(0);
        assertThat(audited.get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get("detail").get("state_to").asString()).isEqualTo("NOT_ENDORSED");
        JsonNode told = only(smo, "EXEMPTION_LAPSED_NOT_ENDORSED");
        assertThat(told.get("state").asString()).isEqualTo("NOT_ENDORSED");
        assertThat(told.get("link").asString()).isEqualTo("/smo/submissions/" + sub);
    }

    @Test
    void aQ4SmoRecordsAndAVaPocConfirmsTheVaAfterTheCycleHasClosed() {
        registered(List.of(listed("SYS-Q4", "Fourth", SMO_A, CM, "Q4")), List.of());
        String system = idOf("SYS-Q4");
        String id = conditionallyExempted(signedInAs(SMO_A), system,
                exemption(SCHEDULED_VA, "2028-03-20", "Third-party VA booked with Acme, PO 4472.", null));
        today("2028-03-06");
        arrange.cycleLifecycle();
        today("2028-03-25");

        recordedVa(signedInAs(SMO_A), id, vaHappened("2028-03-21", "Acme report AC-2028-03 received."));
        JsonNode confirmed = confirmedVa(otherVaPoc(), id);

        assertThat(confirmed.get("conditional").asBoolean()).isFalse();
        assertThat(submission(signedInAs(SMO_A), confirmed.get("submissionId").asString()).get("state").asString())
                .isEqualTo(EXEMPTED);
    }

    /** SYS-x's card on the Quarter board of Q3 of the Cycle running today, as this reader sees it. */
    private static JsonNode boardCard(ApiClient reader, String systemCode) {
        var response = reader.get("/api/dashboards/quarter-board?quarter=3&cycle=2027");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode column : ApiClient.json(response).get("columns")) {
            for (JsonNode card : column.get("systems")) {
                if (card.get("systemId").asString().equals(systemCode)) {
                    return card;
                }
            }
        }
        throw new AssertionError("no " + systemCode + " on the board");
    }

    /** SYS-x's item in the Cyber Manager's inbox for VA Cycle 2027. */
    private static JsonNode cmInboxItem(ApiClient cm, String systemCode) {
        var response = cm.get("/api/dashboards/cm-inbox?cycle=2027");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode item : ApiClient.json(response).get("systems")) {
            if (item.get("systemId").asString().equals(systemCode)) {
                return item;
            }
        }
        throw new AssertionError("no " + systemCode + " in the inbox");
    }
}
