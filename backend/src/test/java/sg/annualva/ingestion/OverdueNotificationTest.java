package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 92 with the Overdue sweep (ticket 36): when a window passes, the person whose step it is is told, once. Q3 of VA
 * Cycle 2027: the Submission Window ends on 15 August, the Review Window on 29 August and the Quarter on 31 October.
 */
class OverdueNotificationTest extends NotificationSeam1 {

    private void sweepOn(String businessDay) {
        today(businessDay);
        arrange.overdueSweep();
    }

    @Test
    void aDraftAndANotStartedSystemPastTheSubmissionWindowTellTheirSmoOnce() {
        Draft draft = draft();
        String sub = draft.submissionId();

        sweepOn("2027-08-16");
        sweepOn("2027-08-17");

        JsonNode item = only(signedInAs(SMO_A), "SUBMIT_OVERDUE");
        assertThat(item.get("state").asString()).isEqualTo("DRAFT");
        assertThat(item.get("submissionId").asString()).isEqualTo(sub);
        assertThat(item.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(item.get("link").asString()).isEqualTo("/smo/submissions/" + sub);
        assertThat(item.get("message").asString()).isEqualTo("Overdue: prepare and submit the VA Submission.");
        JsonNode notStarted = only(signedInAs(SMO_B), "SUBMIT_OVERDUE");
        assertThat(notStarted.get("systemCode").asString()).isEqualTo("SYS-F2");
        assertThat(notStarted.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(notStarted.get("submissionId").isNull()).isTrue();
        assertThat(notStarted.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(notStarted.get("link").asString()).isEqualTo("/smo");
        assertThat(actions(signedInAs(SMO_A))).as("a second pass flags nothing new").hasSize(1);
    }

    @Test
    void aReviewPastTheReviewWindowTellsTheCyberManager() {
        Draft draft = inReview();

        sweepOn("2027-08-30");

        JsonNode item = only(signedInAs(CM), "REVIEW_OVERDUE");
        assertThat(item.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(item.get("dueOn").asString()).isEqualTo("2027-08-29");
        assertThat(item.get("link").asString()).isEqualTo("/cyber-manager/submissions/" + draft.submissionId());
        assertThat(actions(signedInAs(SMO_A))).as("not the SMO/Tech Lead's step").isEmpty();
    }

    @Test
    void aReworkPastItsWindowTellsTheSmo() {
        Draft draft = inReview();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, draft.submissionId(), findingIds(cm, draft.submissionId()).get(0), DISPUTED, "Show the upgrade.");
        returned(cm, draft.submissionId(), null);

        sweepOn("2027-08-30");

        JsonNode item = only(signedInAs(SMO_A), "SUBMIT_OVERDUE");
        assertThat(item.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(item.get("link").asString()).isEqualTo("/smo/submissions/" + draft.submissionId() + "/rework");
    }

    @Test
    void anEndorsementPastTheQuartersLastDayTellsBothVaPocsAndEitherEndorsingClearsEveryItemOfIt() {
        Draft draft = awaitingEndorsement();

        sweepOn("2027-11-01");
        JsonNode overdue = only(vaPoc(), "ENDORSE_OVERDUE");
        assertThat(overdue.get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(overdue.get("dueOn").asString()).isEqualTo("2027-10-31");
        assertThat(overdue.get("handledBy").isNull()).isTrue();
        endorsed(otherVaPoc(), draft.submissionId());

        ApiClient one = vaPoc();
        assertThat(only(one, "ENDORSE_OVERDUE").get("handledBy").asString()).isEqualTo("POC Two");
        assertThat(only(one, "ENDORSE").get("handledBy").asString()).isEqualTo("POC Two");
    }
}
