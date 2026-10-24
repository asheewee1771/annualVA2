package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 19 in the inbox: once the SMO/Tech Lead starts a VA Submission the System is no longer Not Started. It shows as
 * Draft with the submission's id (so the screen can open it), the dates the submission was started with, and the next
 * action to prepare it; the System's other rows are unchanged, and nobody else's inbox shows it.
 */
class SmoInboxStartedTest extends InboxSeam1 {

    private void registerSystems() {
        registered(
                List.of(listed("SYS-D1", "Draft one", SMO_A, CM, "Q3"),
                        listed("SYS-D2", "Draft two", SMO_A, CM, "Q4"),
                        listed("SYS-D3", "Draft three", SMO_B, CM, "Q3")),
                List.of());
    }

    private static String idOf(JsonNode inbox, String systemId) {
        return item(inbox, "systems", systemId).get("id").asString();
    }

    private static ApiClient.Response start(ApiClient client, String systemUuid) {
        return client.postJson("/api/submissions", "{\"systemId\":\"" + systemUuid + "\"}");
    }

    @Test
    void aStartedSystemShowsAsADraftWithItsSubmissionAndTheNextActionIsToPrepareIt() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        var response = start(smo, idOf(inbox(smo), "SYS-D1"));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode submission = ApiClient.json(response);

        JsonNode draft = item(inbox(smo), "systems", "SYS-D1");

        assertThat(draft.get("state").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("submissionId").asString()).isEqualTo(submission.get("id").asString());
        assertThat(draft.get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(draft.get("reviewDue").asString()).isEqualTo("2027-08-29");
        assertThat(draft.get("endorseDue").asString()).isEqualTo("2027-10-31");
        assertThat(draft.get("nextAction").asString()).isEqualTo("PREPARE_SUBMISSION");
        assertThat(draft.get("nextActionDueOn").asString()).isEqualTo("2027-08-15");
        assertThat(draft.get("group").asString()).isEqualTo("NEEDS_YOUR_ACTION");
    }

    @Test
    void aSubmittedSystemWaitsOnTheReviewAndOneReturnedForReworkNeedsTheSmoAgain() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String submission = ApiClient.json(start(smo, idOf(inbox(smo), "SYS-D1"))).get("id").asString();
        arrange.submissionState(submission, "IN_REVIEW");

        JsonNode inReview = item(inbox(smo), "systems", "SYS-D1");

        assertThat(inReview.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(inReview.get("nextAction").asString()).isEqualTo("AWAIT_REVIEW");
        assertThat(inReview.get("nextActionDueOn").asString()).isEqualTo("2027-08-29");
        assertThat(inReview.get("group").asString()).isEqualTo("WAITING_ON_OTHERS");
        arrange.submissionState(submission, "RETURNED_FOR_REWORK");
        JsonNode returned = item(inbox(smo), "systems", "SYS-D1");
        assertThat(returned.get("state").asString()).isEqualTo("RETURNED_FOR_REWORK");
        assertThat(returned.get("nextAction").asString()).isEqualTo("PREPARE_SUBMISSION");
        assertThat(returned.get("nextActionDueOn").asString()).as("it waits on the Review Window, by the Overdue rule")
                .isEqualTo("2027-08-29");
        assertThat(returned.get("group").asString()).isEqualTo("NEEDS_YOUR_ACTION");
    }

    @Test
    void aSystemNotStartedStaysNotStartedWithNoSubmissionAndTheOtherSmosInboxIsUnchanged() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        start(smo, idOf(inbox(smo), "SYS-D1"));

        JsonNode mine = inbox(smo);
        JsonNode untouched = item(mine, "systems", "SYS-D2");
        JsonNode theirs = item(inbox(signedInAs(SMO_B)), "systems", "SYS-D3");

        assertThat(untouched.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(untouched.get("submissionId").isNull()).isTrue();
        assertThat(untouched.get("nextAction").asString()).isEqualTo("START_SUBMISSION");
        assertThat(theirs.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(theirs.get("submissionId").isNull()).isTrue();
    }

    @Test
    void aSubmissionIsShownInItsOwnCycleOnly() {
        registered(listed("SYS-D4", "Draft four", SMO_A, CM, "Q3"));
        today("2028-02-07");
        ApiClient smo = signedInAs(SMO_A);
        var response = smo.postJson("/api/submissions",
                "{\"systemId\":\"" + idOf(inbox(smo, 2027), "SYS-D4") + "\",\"cycle\":2027}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);

        assertThat(item(inbox(smo, 2027), "systems", "SYS-D4").get("state").asString()).isEqualTo("DRAFT");
        assertThat(item(inbox(smo, 2028), "systems", "SYS-D4").get("state").asString()).isEqualTo("NOT_STARTED");
    }

    @Test
    void aSubmissionWithTheVaPocsWaitsOnThemAndAFinalOneIsDoneWithNothingNext() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String submission = ApiClient.json(start(smo, idOf(inbox(smo), "SYS-D1"))).get("id").asString();

        arrange.submissionState(submission, "AWAITING_ENDORSEMENT");
        JsonNode awaiting = item(inbox(smo), "systems", "SYS-D1");
        arrange.submissionState(submission, "EXEMPTION_REQUESTED");
        JsonNode frozen = item(inbox(smo), "systems", "SYS-D1");
        arrange.submissionState(submission, "ENDORSED");
        JsonNode endorsed = item(inbox(smo), "systems", "SYS-D1");

        assertThat(awaiting.get("state").asString()).isEqualTo("AWAITING_ENDORSEMENT");
        assertThat(awaiting.get("nextAction").asString()).isEqualTo("AWAIT_ENDORSEMENT");
        assertThat(awaiting.get("nextActionDueOn").asString()).isEqualTo("2027-10-31");
        assertThat(awaiting.get("group").asString()).isEqualTo("WAITING_ON_OTHERS");
        assertThat(frozen.get("state").asString()).isEqualTo("EXEMPTION_REQUESTED");
        assertThat(frozen.get("nextAction").asString()).isEqualTo("AWAIT_EXEMPTION_DECISION");
        assertThat(frozen.get("nextActionDueOn").isNull()).as("frozen: no window").isTrue();
        assertThat(frozen.get("group").asString()).isEqualTo("WAITING_ON_OTHERS");
        assertThat(endorsed.get("state").asString()).isEqualTo("ENDORSED");
        assertThat(endorsed.get("nextAction").isNull()).isTrue();
        assertThat(endorsed.get("nextActionDueOn").isNull()).isTrue();
        assertThat(endorsed.get("group").asString()).isEqualTo("DONE");
    }
}
