package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Story 57 (ticket 30): the Cyber Manager passes a VA Submission to the VA POCs only once every Finding and every
 * not-applicable justification is confirmed; until then the pass lists what is left.
 */
class PassToEndorsementTest extends ReturnSeam1 {

    @Test
    void onceEverythingIsConfirmedItGoesToTheVaPocs() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        clock.advance(Duration.ofMinutes(5));
        confirmEverything(cm, sub);
        events.reset();

        JsonNode answered = passed(cm, sub);

        assertThat(answered.get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        JsonNode acted = answered.get("acted");
        assertThat(acted).as("SUBMIT and REVIEW, once each").hasSize(2);
        assertThat(acted.get(1).get("step").asString()).isEqualTo("REVIEW");
        assertThat(acted.get(1).get("userId").asString()).isEqualTo(userId(cm));

        assertThat(events.published()).hasSize(1);
        SubmissionEvent event = events.published().getFirst();
        assertThat(event.submissionId()).isEqualTo(UUID.fromString(sub));
        assertThat(event.systemId()).isEqualTo(UUID.fromString(draft.systemId()));
        assertThat(event.from().name()).isEqualTo("IN_REVIEW");
        assertThat(event.to().name()).isEqualTo(AWAITING_ENDORSEMENT);

        JsonNode audited = entries("SUBMISSION_PASSED", draft.systemId());
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get(0).get("detail").get("state_from").asString()).isEqualTo("IN_REVIEW");
        assertThat(audited.get(0).get("detail").get("state_to").asString()).isEqualTo(AWAITING_ENDORSEMENT);
    }

    @Test
    void aPassShowsOnTheCycleMatrixAsTheReviewedMilestoneOnItsBusinessDate() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);
        assertThat(matrixRow("SYS-F1").get("reviewed").get("doneOn").isNull()).as("not before the pass").isTrue();
        today("2027-08-20");
        cm = signedInAs(CM);

        passed(cm, sub);

        JsonNode row = matrixRow("SYS-F1");
        assertThat(row.get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(row.get("reviewed").get("doneOn").asString()).isEqualTo("2027-08-20");
        assertThat(row.get("reviewed").get("current").asBoolean()).isFalse();
    }

    @Test
    void withAnythingUnconfirmedItIsRefusedListingWhatIsLeft() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);

        JsonNode untouched = refusedReasons(passToEndorsement(cm, sub));

        assertThat(codes(untouched)).containsExactly("findings-awaiting-review", "justification-awaiting-review");
        assertThat(reason(untouched, "findings-awaiting-review").get("message").asString()).startsWith("2 Findings");
        assertThat(reason(untouched, "findings-awaiting-review").get("reportType").isNull()).isTrue();
        assertThat(reason(untouched, "justification-awaiting-review").get("reportType").asString()).isEqualTo(BURP_SUITE);
        assertThat(reason(untouched, "justification-awaiting-review").get("message").asString()).contains("Burp Suite");

        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), DISPUTED, "Show the fix.");
        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "It has a web console.");
        JsonNode partly = refusedReasons(passToEndorsement(cm, sub));

        assertThat(codes(partly)).containsExactly("findings-awaiting-review", "findings-disputed",
                "justification-disputed");
        assertThat(reason(partly, "findings-awaiting-review").get("message").asString()).startsWith("1 Finding is");
        assertThat(reason(partly, "findings-disputed").get("message").asString()).startsWith("1 Finding is");

        assertThat(submission(cm, sub).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(events.published()).as("only the submit").hasSize(1);
        JsonNode refused = entries("SUBMISSION_PASSED", draft.systemId());
        assertThat(refused).hasSize(2);
        assertThat(refused.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get(0).get("detail").get("reason").asString()).isEqualTo("not-confirmed");
        assertThat(refused.get(0).get("detail").get("count").asInt()).isEqualTo(3);
    }

    @Test
    void theReviewScreenReadsWhatStandsInTheWayOfEachMoveAsTheCommandsJudgeIt() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        JsonNode before = readiness(cm, sub);
        assertThat(before.get("review").get("returnable").asBoolean()).as("nothing Disputed yet").isFalse();
        assertThat(codes(before.get("review").get("endorsementBlockers")))
                .isEqualTo(codes(refusedReasons(passToEndorsement(cm, sub))));

        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "It has a web console.");
        JsonNode disputed = readiness(cm, sub);
        assertThat(disputed.get("review").get("returnable").asBoolean()).isTrue();
        assertThat(codes(disputed.get("review").get("endorsementBlockers")))
                .containsExactly("findings-awaiting-review", "justification-disputed");

        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        for (String finding : findingIds(cm, sub)) {
            reviewed(cm, sub, finding, CONFIRMED, null);
        }
        JsonNode confirmed = readiness(cm, sub);
        assertThat(confirmed.get("review").get("returnable").asBoolean()).isFalse();
        assertThat(confirmed.get("review").get("endorsementBlockers")).isEmpty();
    }

    @Test
    void withNothingMarkedNotApplicableOnlyTheFindingsCount() {
        Draft draft = draft();
        String sub = draft.submissionId();
        modeSet(draft.smo(), sub, DEPENDENCY_CHECK, mode(MANUAL, "No dependencies to scan were found."));
        modeSet(draft.smo(), sub, BURP_SUITE, mode(MANUAL, "Tested by hand."));
        modeSet(draft.smo(), sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        proposeEvery(draft);
        submitted(draft.smo(), sub);
        ApiClient cm = signedInAs(CM);
        assertThat(codes(refusedReasons(passToEndorsement(cm, sub)))).containsExactly("findings-awaiting-review");
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), CONFIRMED, null);

        assertThat(passed(cm, sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
    }

    @Test
    void onlyAVaSubmissionInReviewIsPassedAndOnlyByItsCyberManager() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);

        assertThat(passToEndorsement(draft.smo(), sub).status()).as("not a Cyber Manager").isEqualTo(403);
        assertThat(passToEndorsement(vaPoc(), sub).status()).as("not a Cyber Manager").isEqualTo(403);
        assertNotFound(passToEndorsement(signedInAs(CM_OTHER), sub));
        passed(cm, sub);

        assertConflict(passToEndorsement(cm, sub), "invalid-transition");
        assertConflict(returnForRework(cm, sub, null), "invalid-transition");
        JsonNode refused = entries("SUBMISSION_PASSED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("state-not-reviewable");
    }

    /** The reasons of a refused pass: it is a 409 {@code not-confirmed} problem, and these are its reasons in order. */
    private static JsonNode refusedReasons(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        JsonNode problem = ApiClient.json(response);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/not-confirmed");
        return problem.get("reasons");
    }
}
