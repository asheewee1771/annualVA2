package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;

/**
 * Story 56 (ticket 32): when a VA Submission comes back, the Cyber Manager re-reviews only the Findings whose proposals
 * changed since their last review; what they confirmed stays confirmed, and the list narrows to what changed.
 */
class ReReviewTest extends ReturnSeam1 {

    private static final String CHANGED = "changedSinceLastReview=true";
    private static final String UNCHANGED = "changedSinceLastReview=false";

    /**
     * SYS-F1's VA Submission reviewed in full and returned: its first Finding Disputed, its second and the Burp Suite
     * justification confirmed. The SMO/Tech Lead then proposes something else for the Disputed Finding and resubmits.
     */
    private record Resubmitted(Draft draft, String changed, String confirmed) {}

    private Resubmitted resubmitted() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), DISPUTED, "Show the fix.");
        reviewed(cm, sub, ids.get(1), CONFIRMED, null);
        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        returned(cm, sub, "One Finding needs another look.");
        proposed(draft.smo(), ids.get(0), remediated("Upgraded to 2.0."));
        submitted(draft.smo(), sub);
        return new Resubmitted(draft, ids.get(0), ids.get(1));
    }

    @Test
    void aResubmissionKeepsWhatWasConfirmedAndListsOnlyWhatChangedSinceItsLastReview() {
        Resubmitted back = resubmitted();
        String sub = back.draft().submissionId();
        ApiClient cm = signedInAs(CM);

        assertThat(findingById(cm, sub, back.confirmed()).get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(findingById(cm, sub, back.changed()).get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(report(cm, sub, BURP_SUITE).get("reviewState").asString()).isEqualTo(CONFIRMED);

        assertThat(listedIds(cm, sub, CHANGED)).containsExactly(back.changed());
        assertThat(listedIds(cm, sub, UNCHANGED)).containsExactly(back.confirmed());
        assertThat(ApiClient.json(cm.get(SUBMISSIONS + "/" + sub + "/findings?" + CHANGED)).get("total").asInt())
                .as("the total is of the narrowed list").isEqualTo(1);
        assertThat(listedIds(back.draft().smo(), sub, CHANGED)).as("the same list for every reader")
                .containsExactly(back.changed());
    }

    @Test
    void passingAfterAResubmissionNeedsOnlyTheChangedFindingsConfirmed() {
        Resubmitted back = resubmitted();
        String sub = back.draft().submissionId();
        ApiClient cm = signedInAs(CM);
        assertConflict(passToEndorsement(cm, sub), "not-confirmed");

        for (String finding : listedIds(cm, sub, CHANGED)) {
            reviewed(cm, sub, finding, CONFIRMED, null);
        }

        assertThat(listedIds(cm, sub, CHANGED)).as("reviewed again, so no longer changed").isEmpty();
        assertThat(passed(cm, sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
    }

    @Test
    void aFindingNeverReviewedOrReviewedAsItIsHasNotChangedSinceItsLastReview() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        assertThat(listedIds(cm, sub, CHANGED)).as("nothing reviewed yet").isEmpty();

        reviewed(cm, sub, ids.get(0), DISPUTED, "Show the fix.");

        assertThat(listedIds(cm, sub, CHANGED)).as("its review is of its current proposal").isEmpty();
        assertThat(listedIds(cm, sub, UNCHANGED)).containsExactlyElementsOf(ids);
    }

    @Test
    void aDisputedFindingLeftAsItWasStaysDisputedAndUnchangedAndStillStopsThePass() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), DISPUTED, "Show the fix.");
        reviewed(cm, sub, ids.get(1), CONFIRMED, null);
        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        returned(cm, sub, null);
        submitted(draft.smo(), sub);

        assertThat(findingById(cm, sub, ids.get(0)).get("reviewState").asString()).isEqualTo(DISPUTED);
        assertThat(listedIds(cm, sub, CHANGED)).isEmpty();
        assertConflict(passToEndorsement(cm, sub), "not-confirmed");
    }

    @Test
    void aConfirmedFindingProposedAgainDuringReworkIsChangedEvenWithTheSameStatus() {
        Resubmitted back = resubmitted();
        String sub = back.draft().submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, back.changed(), DISPUTED, "Still no evidence.");
        returned(cm, sub, null);

        proposed(back.draft().smo(), back.confirmed(), falsePositive("Not reachable in this System."));
        submitted(back.draft().smo(), sub);

        assertThat(listedIds(cm, sub, CHANGED)).as("a new proposal is a change, whatever it says")
                .containsExactly(back.confirmed());
        assertThat(findingById(cm, sub, back.confirmed()).get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(findingById(cm, sub, back.changed()).get("reviewState").asString()).isEqualTo(DISPUTED);
    }

    @Test
    void theFilterTakesOnlyTrueOrFalse() {
        Draft draft = inReview();

        assertThat(signedInAs(CM).get(SUBMISSIONS + "/" + draft.submissionId() + "/findings?changedSinceLastReview=maybe")
                .status()).isEqualTo(400);
    }
}
