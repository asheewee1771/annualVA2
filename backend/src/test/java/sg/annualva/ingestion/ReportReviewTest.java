package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 50 and ADR 0030 item 3 (ticket 28): the Cyber Manager confirms or Disputes the justification of a Scan Report marked
 * not applicable, like a Finding. The review keeps the wording it was about, because a rewrite overwrites the
 * justification and puts the review back to PENDING.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportReviewTest extends ReviewSeam1 {

    private static final String WORDING = "The System has no web interface.";

    @Test
    void theCyberManagerConfirmsANotApplicableJustificationAndTheReviewKeepsItsWording() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);

        int version = justificationVersion(cm, sub, BURP_SUITE);

        var response = reviewReport(cm, sub, BURP_SUITE, justificationReview(CONFIRMED, "Agreed, API only.", version));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode review = ApiClient.json(response);
        assertThat(review.get("decision").asString()).isEqualTo(CONFIRMED);
        assertThat(review.get("comment").asString()).isEqualTo("Agreed, API only.");
        assertThat(review.get("justification").asString()).isEqualTo(WORDING);
        assertThat(review.get("justificationVersion").asInt()).isEqualTo(version);
        assertThat(review.get("proposalId").isNull()).isTrue();
        assertThat(review.get("reviewedBy").asString()).isEqualTo(userId(cm));
        JsonNode burp = report(draft.smo(), sub, BURP_SUITE);
        assertThat(burp.get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(burp.get("latestReview")).isEqualTo(review);
        assertThat(report(cm, sub, DEPENDENCY_CHECK).get("latestReview").isNull()).as("never reviewed").isTrue();
        JsonNode acted = submission(draft.smo(), sub).get("acted");
        List<String> steps = new ArrayList<>();
        acted.forEach(each -> steps.add(each.get("step").asString()));
        assertThat(steps).containsExactlyInAnyOrder("SUBMIT", "REVIEW");
    }

    @Test
    void afterADisputeAndARewriteTheDisputedWordingStaysBesideItsReview() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "There is an admin console.");
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");

        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "The admin console is not reachable from outside."));

        JsonNode burp = report(cm, sub, BURP_SUITE);
        assertThat(burp.get("justification").asString()).isEqualTo("The admin console is not reachable from outside.");
        assertThat(burp.get("reviewState").asString()).as("the new wording awaits review").isEqualTo("PENDING");
        JsonNode latest = burp.get("latestReview");
        assertThat(latest.get("decision").asString()).isEqualTo(DISPUTED);
        assertThat(latest.get("comment").asString()).isEqualTo("There is an admin console.");
        assertThat(latest.get("justification").asString()).as("the wording that was Disputed").isEqualTo(WORDING);

        submitted(draft.smo(), sub);
        JsonNode confirmed = reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        assertThat(confirmed.get("justification").asString())
                .isEqualTo("The admin console is not reachable from outside.");
        assertThat(confirmed.get("comment").isNull()).isTrue();
    }

    @Test
    void aReviewIsShownOnlyBesideANotApplicableJustification() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        reportReviewed(signedInAs(CM), sub, BURP_SUITE, CONFIRMED, null);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");

        JsonNode uploaded = modeSet(draft.smo(), sub, BURP_SUITE, mode(UPLOAD, null));

        assertThat(uploaded.get("latestReview").isNull()).isTrue();
        assertThat(report(draft.smo(), sub, BURP_SUITE).get("latestReview").isNull()).isTrue();
    }

    @Test
    void onlyAScanReportMarkedNotApplicableHasAJustificationToReview() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);

        assertConflict(reviewReport(cm, sub, SECURE_CONFIGURATION, justificationReview(CONFIRMED, null, 0)), "not-reviewable");
        assertConflict(reviewReport(cm, sub, DEPENDENCY_CHECK, justificationReview(CONFIRMED, null, 0)), "not-reviewable");

        JsonNode entries = entries("REPORT_REVIEWED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("not-reviewable");
        assertThat(entries.get(0).get("detail").get("report_type").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(entries.get(1).get("detail").get("report_type").asString()).isEqualTo(SECURE_CONFIGURATION);
    }

    @Test
    void theFieldsAreCheckedAndAProposalIsNotNamed() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);

        int version = justificationVersion(cm, sub, BURP_SUITE);
        assertInvalidField(reviewReport(cm, sub, BURP_SUITE, justificationReview("MAYBE", null, version)), "decision");
        assertInvalidField(reviewReport(cm, sub, BURP_SUITE, justificationReview(DISPUTED, "", version)), "comment");
        assertInvalidField(reviewReport(cm, sub, BURP_SUITE, justificationReview(CONFIRMED, null, null)),
                "justificationVersion");
        assertInvalidField(reviewReport(cm, sub, BURP_SUITE,
                "{\"decision\":\"CONFIRMED\",\"justificationVersion\":\"one\"}"), "justificationVersion");
        assertInvalidField(reviewReport(cm, sub, BURP_SUITE,
                "{\"decision\":\"CONFIRMED\",\"justificationVersion\":\"-1\"}"), "justificationVersion");
        assertInvalidField(reviewReport(cm, sub, BURP_SUITE, review(CONFIRMED, null, sub)), "proposalId");
        String finding = findingIds(cm, sub).getFirst();
        assertInvalidField(reviewFinding(cm, finding, review(CONFIRMED, null, currentProposalId(cm, sub, finding))
                .replace("}", ",\"justificationVersion\":\"0\"}")), "justificationVersion");
        assertThat(report(cm, sub, BURP_SUITE).get("reviewState").asString()).isEqualTo("PENDING");
    }

    @Test
    void onlyTheCyberManagerOfTheSystemReviewsItAndOnlyInReview() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        String body = justificationReview(CONFIRMED, null, justificationVersion(draft.smo(), sub, BURP_SUITE));
        String auditor = "report.review.auditor@ci.annualva.test";
        ApiClient auditorBrowser = signedInAs(auditor);
        arrange.grant("AUDITOR", auditor, null, "GRANT", "2027-12-31T00:00:00Z");

        assertConflict(reviewReport(signedInAs(CM), sub, BURP_SUITE, body), "invalid-transition");
        submitted(draft.smo(), sub);
        assertThat(reviewReport(draft.smo(), sub, BURP_SUITE, body).status()).as("the SMO/Tech Lead").isEqualTo(403);
        assertThat(reviewReport(vaPoc(), sub, BURP_SUITE, body).status()).as("a VA POC").isEqualTo(403);
        assertThat(reviewReport(auditorBrowser, sub, BURP_SUITE, body).status()).as("an Auditor").isEqualTo(403);
        assertThat(reviewReport(signedInAs("report.review.nobody@ci.annualva.test"), sub, BURP_SUITE, body).status())
                .as("a person with no role").isEqualTo(403);
        var anonymous = reviewReport(anotherBrowser().withCsrf(), sub, BURP_SUITE, body);
        assertThat(anonymous.status()).as("no session").isEqualTo(401);
        assertThat(ApiClient.json(anonymous).get("type").asString()).isEqualTo("https://annualva/problems/unauthenticated");
        assertNotFound(reviewReport(signedInAs(CM_OTHER), sub, BURP_SUITE, body));

        JsonNode refused = entries("REPORT_REVIEWED", draft.systemId());
        assertThat(refused).hasSize(1);
        assertThat(refused.get(0).get("detail").get("reason").asString()).isEqualTo("state-not-reviewable");
        JsonNode burp = report(draft.smo(), sub, BURP_SUITE);
        assertThat(burp.get("reviewState").asString()).as("nothing kept").isEqualTo("PENDING");
        assertThat(burp.get("latestReview").isNull()).isTrue();
        assertThat(submission(draft.smo(), sub).get("acted")).as("no reviewing step recorded").hasSize(1);
    }

    @Test
    void aReviewOfAJustificationRewrittenSinceItWasReadIsRefusedAndAudited() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "There is an admin console.");
        int seen = justificationVersion(cm, sub, BURP_SUITE);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");
        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "The admin console is internal only."));
        submitted(draft.smo(), sub);

        var stale = reviewReport(cm, sub, BURP_SUITE, justificationReview(CONFIRMED, null, seen));

        assertConflict(stale, "justification-changed");
        assertThat(stale.body()).doesNotContain("admin console");
        JsonNode burp = report(cm, sub, BURP_SUITE);
        assertThat(burp.get("reviewState").asString()).as("the rewrite still awaits review").isEqualTo("PENDING");
        assertThat(burp.get("justificationVersion").asInt()).isGreaterThan(seen);
        assertThat(burp.get("latestReview").get("decision").asString()).isEqualTo(DISPUTED);
        assertThat(burp.get("latestReview").get("justificationVersion").asInt()).isEqualTo(seen);
        JsonNode refused = entries("REPORT_REVIEWED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("justification-changed");
        assertThat(refused.get("detail").get("report_type").asString()).isEqualTo(BURP_SUITE);
        assertThat(refused.toString()).doesNotContain("admin console");
    }

    @Test
    void theSameJustificationSetAgainKeepsItsVersionAndItsReview() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        int seen = justificationVersion(cm, sub, BURP_SUITE);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");

        JsonNode again = modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, WORDING));
        JsonNode away = modeSet(draft.smo(), sub, BURP_SUITE, mode(UPLOAD, null));
        JsonNode back = modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, WORDING));

        assertThat(again.get("justificationVersion").asInt()).as("nothing new to review").isEqualTo(seen);
        assertThat(again.get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(away.get("justificationVersion").asInt()).isEqualTo(seen + 1);
        assertThat(back.get("justificationVersion").asInt()).as("marked not applicable again").isEqualTo(seen + 2);
        assertThat(back.get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(report(cm, sub, BURP_SUITE)).as("as read back").isEqualTo(back);
    }

    @Test
    void aReviewIsAuditedWithTheScanReportAndTheDecisionAndNothingWritten(CapturedOutput output) {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "No web front end " + SENTINEL));
        submitted(draft.smo(), sub);
        ApiClient cm = signedInAs(CM);

        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "Prove it " + SENTINEL);
        List<String> bodies = List.of(
                reviewReport(cm, sub, BURP_SUITE, justificationReview(DISPUTED, SENTINEL + "x".repeat(4001), 1)).body(),
                reviewReport(cm, sub, BURP_SUITE, justificationReview(SENTINEL, null, 1)).body(),
                reviewReport(cm, sub, BURP_SUITE, justificationReview(CONFIRMED, SENTINEL, 99)).body(),
                reviewReport(cm, sub, SECURE_CONFIGURATION, justificationReview(DISPUTED, SENTINEL, 0)).body());

        JsonNode success = entries("REPORT_REVIEWED", draft.systemId()).get(2);
        assertThat(success.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(success.get("detail").propertyNames()).containsExactlyInAnyOrder("report_type", "decision");
        assertThat(success.get("detail").get("decision").asString()).isEqualTo(DISPUTED);
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("report-reviewed");
        assertThat(String.join("\n", bodies)).as("error bodies").doesNotContain(SENTINEL);
        assertThat(report(draft.smo(), sub, BURP_SUITE).get("latestReview").toString()).contains("Prove it " + SENTINEL);
    }
}
