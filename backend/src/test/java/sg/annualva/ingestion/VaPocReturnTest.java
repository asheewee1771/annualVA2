package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.CapturingMailRelay.Sent;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 66 and 63 (ticket 35): a VA POC returns a VA Submission Awaiting Endorsement to its SMO/Tech Lead with a
 * comment, after a fresh sign-in, to challenge something the Cyber Manager confirmed; the resubmission goes through the
 * Cyber Manager again.
 */
class VaPocReturnTest extends MailSeam1 {

    @Test
    void aVaPocReturnsAVaSubmissionAwaitingEndorsementWithTheirComment() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(1));
        events.reset();

        JsonNode answered = returned(poc, sub, "Show me the evidence for the Remediated CVE.");

        assertThat(answered.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(actedAt(answered, "ENDORSE")).containsExactly(userId(poc));
        ApiClient cm = signedInAs(CM);
        List<String> reviewStates = findingIds(cm, sub).stream()
                .map(finding -> findingById(cm, sub, finding).get("reviewState").asString()).toList();
        assertThat(reviewStates).as("confirmed stays confirmed").isNotEmpty().containsOnly(CONFIRMED);

        JsonNode kept = returns(draft.smo(), sub).get(0);
        assertThat(kept.get("step").asString()).isEqualTo("ENDORSE");
        assertThat(kept.get("comment").asString()).isEqualTo("Show me the evidence for the Remediated CVE.");
        assertThat(kept.get("returnedBy").asString()).isEqualTo(userId(poc));
        assertThat(kept.get("returnedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(kept.get("disputedFindings")).as("nothing is Disputed Awaiting Endorsement").isEmpty();
        assertThat(kept.get("disputedReports")).isEmpty();

        assertThat(events.published()).hasSize(1);
        SubmissionEvent event = events.published().getFirst();
        assertThat(event.submissionId()).isEqualTo(UUID.fromString(sub));
        assertThat(event.from().name()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(event.to().name()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(event.actorId()).isEqualTo(UUID.fromString(userId(poc)));

        JsonNode audited = entries("SUBMISSION_RETURNED", draft.systemId());
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get(0).get("detail").get("state_from").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(audited.get(0).get("detail").get("state_to").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(audited.get(0).get("detail").get("count").asInt()).as("nothing attached").isZero();
        assertThat(audited.get(0).toString()).as("the comment is data, never audited").doesNotContain("Remediated CVE");
    }

    @Test
    void aVaPocReturnTellsTheSmoAndTheCyberManagerAndClearsTheEndorsementForBothVaPocs() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient one = vaPoc();
        clock.advance(Duration.ofMinutes(1));

        returned(one, sub, "Show me the evidence for the Remediated CVE.");

        JsonNode smo = only(draft.smo(), "VA_POC_REWORK");
        assertThat(smo.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(smo.get("message").asString())
                .isEqualTo("A VA POC returned the VA Submission: read their comment, rework it and submit it again.");
        assertThat(smo.get("dueOn").asString()).as("Returned for Rework waits on the Review Window")
                .isEqualTo("2027-08-29");
        assertThat(smo.get("link").asString()).isEqualTo("/smo/submissions/" + sub + "/rework");
        assertThat(smo.toString()).as("a notification carries no comment").doesNotContain("Remediated CVE");
        JsonNode cm = only(signedInAs(CM), "VA_POC_RETURNED");
        assertThat(cm.get("message").asString()).isEqualTo(
                "A VA POC returned the VA Submission from endorsement: re-review what changes once it is submitted "
                + "again.");
        assertThat(cm.get("link").asString()).isEqualTo("/cyber-manager/submissions/" + sub);
        assertThat(cm.toString()).doesNotContain("Remediated CVE");
        assertThat(cm.get("createdAt").asString()).isEqualTo(clock.instant().toString());
        JsonNode sibling = only(otherVaPoc(), "ENDORSE");
        assertThat(sibling.get("handledBy").asString()).as("a return handles the endorsement").isEqualTo("POC One");
        assertThat(sibling.get("handledAt").asString()).isEqualTo(clock.instant().toString());
    }

    @Test
    void aVaPocReturnIsMailedToTheSmoAndTheCyberManagerAsAReturnForReworkWithoutTheComment() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();

        returned(poc, sub, "Show me the evidence for the Remediated CVE.");
        dispatched();

        assertThat(mailTo(SMO_A)).hasSize(1);
        Sent smo = mailTo(SMO_A).getFirst();
        assertThat(smo.templateId()).isEqualTo(REWORK_TEMPLATE);
        assertThat(smo.values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "system_id", "SYS-F1",
                "system_name", "File one",
                "state", "Returned for Rework",
                "action", "A VA POC returned the VA Submission: read their comment, rework it and submit it again.",
                "due_date", "29 August 2027",
                "link", APP_URL + "/smo/submissions/" + sub + "/rework"));
        assertThat(mailTo(CM)).as("submit and pass are in-app only; the return is mailed").hasSize(1);
        Sent cm = mailTo(CM).getFirst();
        assertThat(cm.templateId()).isEqualTo(REWORK_TEMPLATE);
        assertThat(cm.values()).containsEntry("link", APP_URL + "/cyber-manager/submissions/" + sub)
                .containsEntry("action", "A VA POC returned the VA Submission from endorsement: re-review what changes "
                        + "once it is submitted again.");
        assertThat(mailRelay.sent().toString()).as("a mail carries no comment").doesNotContain("Remediated CVE");
    }

    @Test
    void returningNeedsAFreshSignInAndChangesNothingWithoutOne() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(6));
        events.reset();

        var refused = returnForRework(poc, sub, "Show me the evidence.");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("type").asString())
                .isEqualTo("https://annualva/problems/step-up-required");
        assertThat(ApiClient.json(refused).get("stepUpPath").asString())
                .isEqualTo("/auth/step-up?intent=submission-return");
        assertThat(submission(poc, sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(events.published()).isEmpty();
        assertThat(entries("SUBMISSION_RETURNED", draft.systemId())).isEmpty();

        SignIn.stepUp(poc, issuer, "/auth/step-up?intent=submission-return&returnTo=/va-poc/submissions/" + sub,
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        poc.withCsrf();

        assertThat(returned(poc, sub, "Show me the evidence.").get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
    }

    @Test
    void aVaPocReturnNeedsACommentThatIsCleanedAndBounded() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();

        assertInvalidField(returnForRework(poc, sub, null), "comment");
        assertInvalidField(returnForRework(poc, sub, " \u200B\t "), "comment");
        assertInvalidField(returnForRework(poc, sub, "x".repeat(4001)), "comment");
        assertThat(submission(poc, sub).get("state").asString()).as("nothing moved").isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(entries("SUBMISSION_RETURNED", draft.systemId())).as("a bad field is no return").isEmpty();

        returned(poc, sub, "  Look again\u202E at the CVE.\u0007 \u200B");

        assertThat(returns(poc, sub).get(0).get("comment").asString()).as("cleaned like every text a person types")
                .isEqualTo("Look again at the CVE.");
    }

    @Test
    void aCommentOfExactlyTheLimitIsKept() {
        Draft draft = awaitingEndorsement();

        returned(vaPoc(), draft.submissionId(), "y".repeat(4000));

        assertThat(returns(vaPoc(), draft.submissionId()).get(0).get("comment").asString()).hasSize(4000);
    }

    @Test
    void onlyAVaPocReturnsAndOnlyAVaSubmissionAwaitingEndorsement() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();

        assertConflict(returnForRework(poc, sub, "Too early."), "invalid-transition");
        JsonNode refused = entries("SUBMISSION_RETURNED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("state-not-endorsable");
        assertThat(submission(poc, sub).get("acted").toString()).as("a refusal records no step").doesNotContain("ENDORSE");

        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);
        passed(cm, sub);
        events.reset();
        int entriesBefore = entries("SUBMISSION_RETURNED", draft.systemId()).size();
        assertThat(returnForRework(draft.smo(), sub, "Mine.").status()).as("not a VA POC").isEqualTo(403);
        assertThat(submission(poc, sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(events.published()).isEmpty();
        assertThat(entries("SUBMISSION_RETURNED", draft.systemId())).hasSize(entriesBefore);
        assertConflict(returnForRework(cm, sub, "Mine."), "invalid-transition");
        assertThat(entries("SUBMISSION_RETURNED", draft.systemId()).get(0).get("detail").get("reason").asString())
                .as("the Cyber Manager returns only In Review").isEqualTo("state-not-reviewable");
        assertNotFound(returnForRework(poc, UUID.randomUUID().toString(), "Nothing here."));
        returned(poc, sub, "Show me the evidence.");

        assertConflict(returnForRework(otherVaPoc(), sub, "Me too."), "invalid-transition");
        assertThat(returns(poc, sub)).as("returned once").hasSize(1);
    }

    @Test
    void aVaPocWhoReviewedItLeavesTheReturnToTheOtherVaPoc() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        registeredWith(SMO_A, POC_ONE);
        ApiClient pocAsCyberManager = vaPoc();
        confirmEverything(pocAsCyberManager, sub);
        passed(pocAsCyberManager, sub);
        assertNotFound(returnForRework(pocAsCyberManager, UUID.randomUUID().toString(), "Nothing here."));

        assertSodConflict(returnForRework(vaPoc(), sub, "My own review."));

        JsonNode refused = entries("SUBMISSION_RETURNED", draft.systemId()).get(0);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        ApiClient other = otherVaPoc();
        assertThat(actedAt(returned(other, sub, "Show me the evidence."), "ENDORSE")).containsExactly(userId(other));
    }

    @Test
    void aVaPocWhoIsTheSystemsCyberManagerReturnsItInReviewAsItsCyberManager() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        registeredWith(SMO_A, POC_ONE);
        ApiClient pocAsCyberManager = vaPoc();
        reviewed(pocAsCyberManager, sub, findingIds(pocAsCyberManager, sub).getFirst(), DISPUTED, "Show the fix.");

        returned(pocAsCyberManager, sub, null);

        JsonNode kept = returns(pocAsCyberManager, sub).get(0);
        assertThat(kept.get("step").asString()).as("at the reviewing step: no comment needed").isEqualTo("REVIEW");
        assertThat(kept.get("disputedFindings")).hasSize(1);
    }

    @Test
    void aVaPocWhoIsAnotherSystemsCyberManagerReturnsThisOneAsAVaPoc() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        registered(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                listed("SYS-F2", "File two", SMO_B, POC_ONE, "Q3")), List.of());

        returned(vaPoc(), sub, "Show me the evidence.");

        assertThat(returns(vaPoc(), sub).get(0).get("step").asString()).isEqualTo("ENDORSE");
    }

    @Test
    void aVaPocWhoReturnedItCountsAtTheEndorsingStepSoTheyMayEndorseTheResubmissionButNeverReviewIt() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        returned(vaPoc(), sub, "Show me the evidence.");
        submitted(draft.smo(), sub);

        registeredWith(SMO_A, POC_ONE);
        ApiClient pocAsCyberManager = vaPoc();
        String finding = findingIds(pocAsCyberManager, sub).getFirst();
        String proposal = currentProposalId(pocAsCyberManager, sub, finding);
        assertSodConflict(reviewFinding(pocAsCyberManager, finding, review(CONFIRMED, null, proposal)));
        registeredWith(SMO_A, CM);
        passed(signedInAs(CM), sub);

        assertThat(endorsed(vaPoc(), sub).get("state").asString()).isEqualTo(ENDORSED);
    }

    @Test
    void afterTheCycleClosesAVaPocReturnIsRefused() {
        Draft draft = awaitingEndorsement();
        today("2028-03-06");

        assertConflict(returnForRework(vaPoc(), draft.submissionId(), "Too late."), "invalid-transition");

        JsonNode refused = entries("SUBMISSION_RETURNED", draft.systemId()).get(0);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("cycle-closed");
        assertThat(submission(vaPoc(), draft.submissionId()).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
    }

    @Test
    void returnedForReworkAfterAVaPocReturnIsOverdueAfterTheReviewWindowLikeAnyOther() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        today("2027-08-27");
        JsonNode answered = returned(vaPoc(), sub, "Show me the evidence.");
        assertThat(answered.get("overdue").asBoolean()).isFalse();
        today("2027-08-29");
        arrange.overdueSweep();
        assertThat(submission(signedInAs(SMO_A), sub).get("overdue").asBoolean()).as("on the Review Window's last day")
                .isFalse();

        today("2027-08-30");
        arrange.overdueSweep();

        ApiClient smo = signedInAs(SMO_A);
        assertThat(submission(smo, sub).get("overdue").asBoolean()).as("the Review Window has passed").isTrue();
        assertThat(only(smo, "SUBMIT_OVERDUE").get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
    }

    @Test
    void theResubmissionGoesThroughTheCyberManagerWhoReReviewsOnlyWhatChanged() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        returned(vaPoc(), sub, "Is the CVE really remediated?");
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        proposed(draft.smo(), ids.get(0), falsePositive("Not reachable in this System."));

        JsonNode resubmitted = submitted(draft.smo(), sub);

        assertThat(resubmitted.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(actions(cm)).containsExactlyInAnyOrder("REVIEW", "VA_POC_RETURNED", "REVIEW");
        assertThat(listedIds(cm, sub, "changedSinceLastReview=true")).containsExactly(ids.get(0));
        assertThat(findingById(cm, sub, ids.get(1)).get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(report(cm, sub, BURP_SUITE).get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertConflict(passToEndorsement(cm, sub), "not-confirmed");
        reviewed(cm, sub, ids.get(0), CONFIRMED, null);
        assertThat(passed(cm, sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
    }

    @Test
    void theCycleMatrixKeepsTheLastReviewedDateUntilTheNextPass() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        confirmEverything(signedInAs(CM), sub);
        today("2027-08-20");
        passed(signedInAs(CM), sub);
        today("2027-08-23");

        returned(vaPoc(), sub, "Show me the evidence.");

        JsonNode row = matrixRow("SYS-F1");
        assertThat(row.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(row.get("reviewed").get("doneOn").asString()).as("the last pass").isEqualTo("2027-08-20");
        assertThat(row.get("reviewed").get("current").asBoolean()).as("the review is to do again").isTrue();
        submitted(signedInAs(SMO_A), sub);
        today("2027-08-25");
        passed(signedInAs(CM), sub);
        assertThat(matrixRow("SYS-F1").get("reviewed").get("doneOn").asString()).isEqualTo("2027-08-25");
    }

    /** SYS-F1 with this SMO/Tech Lead and Cyber Manager, and SYS-F2 as before: a VA POC applies the Register again. */
    private void registeredWith(String smo, String manager) {
        registered(List.of(listed("SYS-F1", "File one", smo, manager, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3")), List.of());
    }
}
