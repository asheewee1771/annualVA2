package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 65 and 67 (ticket 34): a VA POC endorses a VA Submission Awaiting Endorsement, after a fresh sign-in, and one
 * endorsed after its Quarter's last day is marked Endorsed Late.
 */
class EndorseTest extends EndorseSeam1 {

    @Test
    void aVaPocEndorsesAVaSubmissionAwaitingEndorsement() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        events.reset();
        ApiClient poc = vaPoc();

        JsonNode answered = endorsed(poc, sub);

        assertThat(answered.get("state").asString()).isEqualTo(ENDORSED);
        assertThat(answered.get("endorsedLate").asBoolean()).isFalse();
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(ENDORSED);
        JsonNode acted = answered.get("acted");
        assertThat(acted).as("SUBMIT, REVIEW and ENDORSE, once each").hasSize(3);
        assertThat(actedAt(answered, "ENDORSE")).containsExactly(userId(poc));

        assertThat(events.published()).hasSize(1);
        SubmissionEvent event = events.published().getFirst();
        assertThat(event.submissionId()).isEqualTo(UUID.fromString(sub));
        assertThat(event.systemId()).isEqualTo(UUID.fromString(draft.systemId()));
        assertThat(event.from().name()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(event.to().name()).isEqualTo(ENDORSED);

        JsonNode audited = entries("SUBMISSION_ENDORSED", draft.systemId());
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get(0).get("detail").get("state_from").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(audited.get(0).get("detail").get("state_to").asString()).isEqualTo(ENDORSED);
        assertThat(audited.get(0).get("detail").get("endorsed_late").asBoolean()).isFalse();
    }

    @Test
    void anEndorsementOnTheQuartersLastDayIsOnTimeAndShowsOnTheCycleMatrix() {
        Draft draft = awaitingEndorsement();
        today("2027-10-31");

        JsonNode answered = endorsed(vaPoc(), draft.submissionId());

        assertThat(answered.get("endorsedLate").asBoolean()).as("the Quarter's last day is inside the window").isFalse();
        JsonNode row = matrixRow("SYS-F1");
        assertThat(row.get("state").asString()).isEqualTo(ENDORSED);
        assertThat(row.get("endorsedLate").asBoolean()).isFalse();
        assertThat(row.get("endorsed").get("doneOn").asString()).isEqualTo("2027-10-31");
    }

    @Test
    void anEndorsementAfterTheQuartersLastDayIsEndorsedLateAndNoLongerOverdue() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        today("2027-11-01");
        arrange.overdueSweep();
        ApiClient poc = vaPoc();
        assertThat(submission(poc, sub).get("overdue").asBoolean()).as("the Endorsement Window has passed").isTrue();

        JsonNode answered = endorsed(poc, sub);

        assertThat(answered.get("state").asString()).isEqualTo(ENDORSED);
        assertThat(answered.get("endorsedLate").asBoolean()).isTrue();
        assertThat(answered.get("overdue").asBoolean()).as("Endorsed waits on no window; Endorsed Late keeps it")
                .isFalse();
        JsonNode row = matrixRow("SYS-F1");
        assertThat(row.get("endorsedLate").asBoolean()).isTrue();
        assertThat(row.get("endorsed").get("doneOn").asString()).isEqualTo("2027-11-01");
        JsonNode audited = entries("SUBMISSION_ENDORSED", draft.systemId());
        assertThat(audited.get(0).get("detail").get("endorsed_late").asBoolean()).isTrue();
    }

    @Test
    void endorsingNeedsAFreshSignInAndChangesNothingWithoutOne() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(6));
        events.reset();

        var refused = endorse(poc, sub);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("type").asString())
                .isEqualTo("https://annualva/problems/step-up-required");
        assertThat(ApiClient.json(refused).get("stepUpPath").asString())
                .isEqualTo("/auth/step-up?intent=submission-endorse");
        assertThat(submission(poc, sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(events.published()).isEmpty();
        assertThat(entries("SUBMISSION_ENDORSED", draft.systemId())).isEmpty();

        var flow = SignIn.stepUp(poc, issuer, "/auth/step-up?intent=submission-endorse&returnTo=/va-poc/submissions/" + sub,
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        assertThat(flow.callback().header("location")).endsWith("/va-poc/submissions/" + sub + "?stepup=done");
        poc.withCsrf();

        assertThat(endorsed(poc, sub).get("state").asString()).isEqualTo(ENDORSED);
    }

    @Test
    void onlyAVaPocEndorsesAndOnlyAVaSubmissionAwaitingEndorsement() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();

        assertConflict(endorse(poc, sub), "invalid-transition");
        JsonNode refused = entries("SUBMISSION_ENDORSED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("state-not-endorsable");
        assertThat(submission(poc, sub).get("acted").toString()).as("a refusal records no step").doesNotContain("ENDORSE");

        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);
        passed(cm, sub);
        assertThat(endorse(draft.smo(), sub).status()).as("not a VA POC").isEqualTo(403);
        assertThat(endorse(cm, sub).status()).as("not a VA POC").isEqualTo(403);
        assertNotFound(endorse(poc, UUID.randomUUID().toString()));
        endorsed(poc, sub);

        assertConflict(endorse(otherVaPoc(), sub), "invalid-transition");
        assertThat(entries("SUBMISSION_ENDORSED", draft.systemId())).hasSize(3);
    }
}
