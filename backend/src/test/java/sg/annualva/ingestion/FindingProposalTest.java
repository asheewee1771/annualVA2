package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 35 (ticket 19): the SMO/Tech Lead of a System proposes one of four Finding Statuses for a Finding with the field
 * that status requires. Each proposal is a new row; the Finding's current proposal changes and its review state goes back to
 * PENDING; the earlier proposals are kept. Only the System's own SMO/Tech Lead proposes, and only while the VA Submission is
 * in Draft or Returned for Rework. The action is audited with ids and the fixed status code, and nothing the person wrote
 * reaches a log line, an audit entry or an error body.
 */
@ExtendWith(OutputCaptureExtension.class)
class FindingProposalTest extends ProposalSeam1 {

    @Test
    void eachOfTheFourStatusesIsProposedWithItsFieldAndIsTheFindingsCurrentProposalPendingReview() {
        Draft draft = draftWithFindings();
        String fixed = findingId(draft, "CVE-2021-44228");
        String notReal = findingId(draft, "CVE-2021-45046");
        String accepted = findingId(draft, "CVE-2022-42889");
        String planned = findingId(draft, "CVE-2018-0002");

        JsonNode first = proposed(draft.smo(), fixed, remediated("Upgraded log4j to 2.17.1."));
        JsonNode second = proposed(draft.smo(), notReal, falsePositive("The vulnerable class is not on the class path."));
        JsonNode third = proposed(draft.smo(), accepted, riskAccepted("No fix exists yet.", "A. Tan", "Head of Operations"));
        JsonNode fourth = proposed(draft.smo(), planned, remediationPlanned("Replace the widget in the next release.", LATER));

        assertThat(first.get("status").asString()).isEqualTo("REMEDIATED");
        assertThat(first.get("evidence").asString()).isEqualTo("Upgraded log4j to 2.17.1.");
        assertThat(first.get("id").asString()).isNotBlank();
        assertThat(first.get("proposedAt").asString()).isNotBlank();
        assertThat(second.get("justification").asString()).isEqualTo("The vulnerable class is not on the class path.");
        assertThat(third.get("reason").asString()).isEqualTo("No fix exists yet.");
        assertThat(third.get("approverName").asString()).isEqualTo("A. Tan");
        assertThat(third.get("approverRole").asString()).isEqualTo("Head of Operations");
        assertThat(third.get("approvalMemoFileId").isNull()).as("the memo is optional").isTrue();
        assertThat(fourth.get("plan").asString()).isEqualTo("Replace the widget in the next release.");
        assertThat(fourth.get("targetDate").asString()).isEqualTo(LATER);

        JsonNode page = findings(draft.smo(), draft.submissionId());
        for (var expected : List.of(
                java.util.Map.entry("CVE-2021-44228", first), java.util.Map.entry("CVE-2021-45046", second),
                java.util.Map.entry("CVE-2022-42889", third), java.util.Map.entry("CVE-2018-0002", fourth))) {
            JsonNode finding = byIdentifier(page, expected.getKey());
            assertThat(finding.get("currentProposal")).as(expected.getKey()).isEqualTo(expected.getValue());
            assertThat(finding.get("reviewState").asString()).as("nobody has reviewed it yet").isEqualTo("PENDING");
        }
        JsonNode untouched = byIdentifier(page, "CVE-2020-9488");
        assertThat(untouched.get("currentProposal").isNull()).isTrue();
        assertThat(untouched.get("reviewState").isNull()).as("a Finding with no proposal has nothing to review").isTrue();
    }

    @Test
    void aProposalIsReadByTheCyberManagerAndTheVaPocToo() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        proposed(draft.smo(), id, remediated("Upgraded."));

        for (ApiClient reader : List.of(signedInAs(CM), vaPoc())) {
            JsonNode finding = finding(reader, draft.submissionId(), "CVE-2021-44228");
            assertThat(finding.get("currentProposal").get("status").asString()).isEqualTo("REMEDIATED");
            assertThat(finding.get("reviewState").asString()).isEqualTo("PENDING");
        }
    }

    @Test
    void aNewProposalIsANewRowTheEarlierOnesAreKeptUnchangedAndTheCurrentOneChanges() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        JsonNode first = proposed(draft.smo(), id, falsePositive("Not reachable."));
        JsonNode second = proposed(draft.smo(), id, remediationPlanned("Patch in the next sprint.", LATER));
        JsonNode third = proposed(draft.smo(), id, remediated("Patched in 2.17.1."));

        assertThat(List.of(first.get("id").asString(), second.get("id").asString(), third.get("id").asString()))
                .as("three rows").doesNotHaveDuplicates();
        JsonNode history = ApiClient.json(draft.smo().get(proposalsOf(id)));
        assertThat(history).as("the latest first, every earlier one kept as it was written").containsExactly(third, second, first);
        JsonNode current = finding(draft.smo(), draft.submissionId(), "CVE-2021-44228").get("currentProposal");
        assertThat(current).isEqualTo(third);
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).as("still one Finding").isEqualTo(7);
    }

    @Test
    void aFindingWithNoProposalHasAnEmptyHistory() {
        Draft draft = draftWithFindings();

        var response = draft.smo().get(proposalsOf(findingId(draft, "CVE-2021-44228")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(ApiClient.json(response)).isEmpty();
    }

    @Test
    void aNewProposalPutsAConfirmedOrDisputedReviewBackToPending() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        proposed(draft.smo(), id, falsePositive("Not reachable."));

        for (String state : List.of("CONFIRMED", "DISPUTED")) {
            arrange.findingReview(id, state);
            assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2021-44228").get("reviewState").asString())
                    .isEqualTo(state);

            proposed(draft.smo(), id, remediated("Patched, after " + state + "."));

            JsonNode finding = finding(draft.smo(), draft.submissionId(), "CVE-2021-44228");
            assertThat(finding.get("reviewState").asString()).as("after " + state).isEqualTo("PENDING");
            assertThat(finding.get("currentProposal").get("evidence").asString()).contains(state);
        }
    }

    @Test
    void onlyTheSmoOfTheSystemProposesAndAnyoneElseIsRefusedBeforeAnythingIsRead() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        String body = remediated("Patched.");

        for (ApiClient other : List.of(signedInAs(CM), vaPoc(), signedInAs(CM_OTHER))) {
            var refused = propose(other, id, body);
            assertThat(refused.status()).as("not the SMO/Tech Lead of any System").isEqualTo(403);
        }
        var stranger = propose(signedInAs(SMO_B), id, body);
        assertNotFound(stranger);
        var missing = propose(draft.smo(), "00000000-0000-4000-8000-000000000000", body);
        assertNotFound(missing);
        assertThat(ApiClient.json(stranger).get("detail")).as("the same answer a missing Finding gets")
                .isEqualTo(ApiClient.json(missing).get("detail"));
        assertThat(anotherBrowser().withCsrf().postJson(proposalsOf(id), body).status()).as("not signed in").isEqualTo(401);
        assertThat(api.postJson(proposalsOf(id), body).status()).as("no CSRF token").isEqualTo(403);

        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2021-44228").get("currentProposal").isNull())
                .as("nothing was kept").isTrue();
        assertThat(entries("STATUS_PROPOSED", draft.systemId())).as("the 403s and the 404s are not proposals").isEmpty();
    }

    @Test
    void aProposalNeedsADraftOrAVaSubmissionReturnedForRework() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        for (String state : List.of("IN_REVIEW", "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED",
                "WITHDRAWN", "NOT_ENDORSED")) {
            arrange.submissionState(draft.submissionId(), state);
            var refused = propose(draft.smo(), id, remediated("Patched in " + state + "."));
            assertThat(refused.status()).as(state).isEqualTo(409);
            assertThat(ApiClient.json(refused).get("type").asString()).isEqualTo("https://annualva/problems/invalid-transition");
        }
        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2021-44228").get("currentProposal").isNull())
                .as("nothing was kept").isTrue();

        arrange.submissionState(draft.submissionId(), "RETURNED_FOR_REWORK");
        assertThat(propose(draft.smo(), id, remediated("Patched after rework.")).status()).isEqualTo(201);
        arrange.submissionState(draft.submissionId(), "DRAFT");
        assertThat(propose(draft.smo(), id, remediated("Patched in Draft.")).status()).isEqualTo(201);

        JsonNode refusals = entries("STATUS_PROPOSED", draft.systemId());
        assertThat(refusals).hasSize(9);
        long refused = java.util.stream.StreamSupport.stream(refusals.spliterator(), false)
                .filter(entry -> entry.get("outcome").asString().equals("REFUSED")).count();
        assertThat(refused).as("each refusal is an entry of its own, with a fixed reason").isEqualTo(7);
        for (JsonNode entry : refusals) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
                assertThat(entry.get("findingId").asString()).isEqualTo(id);
            }
        }
    }

    @Test
    void aProposalIsAnAuditEntryWithIdsAndTheStatusCodeAndNothingTheSmoWrote() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        proposed(draft.smo(), id, riskAccepted("Reason " + SENTINEL, "Name " + SENTINEL, "Role " + SENTINEL));
        proposed(draft.smo(), findingId(draft, "CVE-2021-45046"), remediationPlanned("Plan " + SENTINEL, LATER));

        JsonNode entries = entries("STATUS_PROPOSED", draft.systemId());

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("detail").get("finding_status").asString()).isEqualTo("REMEDIATION_PLANNED");
        assertThat(entries.get(1).get("detail").get("finding_status").asString()).isEqualTo("RISK_ACCEPTED");
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
            assertThat(entry.get("systemId").asString()).isEqualTo(draft.systemId());
            assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
            assertThat(entry.get("detail").propertyNames()).containsExactly("finding_status");
        }
        assertThat(entries.get(1).get("findingId").asString()).isEqualTo(id);
    }

    @Test
    void readingTheHistoryIsAnEntryForEveryRoleWithTheCountOnly() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        proposed(draft.smo(), id, falsePositive("First " + SENTINEL));
        proposed(draft.smo(), id, remediated("Second " + SENTINEL));

        assertThat(draft.smo().get(proposalsOf(id)).status()).isEqualTo(200);
        assertThat(signedInAs(CM).get(proposalsOf(id)).status()).isEqualTo(200);
        assertThat(vaPoc().get(proposalsOf(id)).status()).isEqualTo(200);
        assertNotFound(signedInAs(SMO_B).get(proposalsOf(id)));
        assertNotFound(draft.smo().get(proposalsOf("00000000-0000-4000-8000-000000000000")));

        JsonNode entries = entries("FINDING_PROPOSALS_VIEWED", draft.systemId());
        assertThat(entries).as("the owner's read too, and not the refused ones").hasSize(3);
        for (JsonNode entry : entries) {
            assertThat(entry.get("detail").propertyNames()).containsExactly("count");
            assertThat(entry.get("detail").get("count").asInt()).isEqualTo(2);
            assertThat(entry.get("findingId").asString()).isEqualTo(id);
            assertThat(entry.toString()).doesNotContain(SENTINEL);
        }
    }

    @Test
    void nothingTheSmoWroteReachesALogLineAnAuditEntryOrAnErrorBody(CapturedOutput output) {
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");
        String longText = SENTINEL + "x".repeat(4001);

        JsonNode kept = proposed(draft.smo(), id, riskAccepted("Reason " + SENTINEL, "Name " + SENTINEL, "Role " + SENTINEL));
        proposed(draft.smo(), id, remediationPlanned("Plan " + SENTINEL, LATER));
        List<String> bodies = List.of(
                propose(draft.smo(), id, remediated(longText)).body(),
                propose(draft.smo(), id, "{\"status\":\"REMEDIATED\",\"evidence\":\"x\",\"plan\":\"" + SENTINEL + "\"}").body(),
                propose(draft.smo(), id, "{\"status\":\"" + SENTINEL + "\"}").body(),
                propose(draft.smo(), id, remediationPlanned("Plan " + SENTINEL, "2020-01-01")).body(),
                propose(draft.smo(), id, riskAcceptedWithMemo("r " + SENTINEL, "n", "r", "not-an-id-" + SENTINEL)).body(),
                propose(draft.smo(), id, "{\"status\":" + SENTINEL).body(),
                propose(signedInAs(SMO_B), id, remediated("Patched " + SENTINEL)).body(),
                propose(signedInAs(CM), id, remediated("Patched " + SENTINEL)).body());
        arrange.submissionState(draft.submissionId(), "IN_REVIEW");
        String refused = propose(draft.smo(), id, remediated("Patched " + SENTINEL)).body();
        String audit = otherVaPoc().get("/api/audit-entries?size=200").body();

        assertThat(kept.toString()).as("the proposal is data, so it is returned").contains(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("status-proposed");
        assertThat(audit).as("the audit log").doesNotContain(SENTINEL).contains("STATUS_PROPOSED");
        assertThat(String.join("\n", bodies) + refused).as("error bodies").doesNotContain(SENTINEL);
    }

    @Test
    void aVaPocWhoIsAlsoTheSmoOfAnotherSystemCannotProposeForThisSystemsFinding() {
        registered(
                List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-F3", "File three", POC_TWO, CM_OTHER, "Q3")),
                List.of());
        ApiClient smo = signedInAs(SMO_A);
        Draft draft = new Draft(smo, idOf("SYS-F1"), started(smo, idOf("SYS-F1")).get("id").asString());
        settledDc(draft, "dc.xml", sg.annualva.harness.Samples.dependencyCheckSampleXml());
        String id = findingId(draft, "CVE-2021-44228");
        ApiClient poc = otherVaPoc();

        assertThat(poc.get(findingsOf(draft.submissionId())).status()).as("a VA POC reads every System").isEqualTo(200);
        assertNotFound(propose(poc, id, remediated("Patched by someone who is not its SMO.")));

        assertThat(finding(smo, draft.submissionId(), "CVE-2021-44228").get("currentProposal").isNull()).isTrue();
    }

    @Test
    void theTargetDateIsAfterTodayInSingaporeNotInUtc() {
        // 20:00 UTC on 1 August is 04:00 on 2 August in Singapore: today there is 2 August.
        clock.set(java.time.Instant.parse("2027-08-01T20:00:00Z"));
        Draft draft = draftWithFindings();
        String id = findingId(draft, "CVE-2021-44228");

        assertInvalidField(propose(draft.smo(), id, remediationPlanned("Soon.", "2027-08-02")), "targetDate");
        assertThat(propose(draft.smo(), id, remediationPlanned("Soon.", "2027-08-03")).status()).isEqualTo(201);
    }
}
