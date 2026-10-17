package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 37 (ticket 20): {@code POST /api/submissions/{id}/findings/bulk-proposals} applies one Finding Status, with one set
 * of its fields, to every Finding it names. It makes one batch and one ordinary proposal per Finding that carries the
 * batch id, and answers the batch id. The list of Findings is refused as a whole when one of them is not a Finding of this
 * VA Submission, and the whole request when the VA Submission takes no proposals now; the fields are checked exactly as
 * for one Finding. The step is one audit entry with ids, counts and the fixed status code, plus one entry per Finding
 * with the batch id, and nothing the person wrote reaches a log line, an audit entry or an error body.
 */
@ExtendWith(OutputCaptureExtension.class)
class BulkProposalTest extends ProposalSeam1 {

    private static final String MISSING = "00000000-0000-4000-8000-000000000000";

    @Autowired
    private JdbcClient application;

    private static String bulkOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/findings/bulk-proposals";
    }

    /** The proposal's body with the list of Finding ids in front of its fields. */
    private static String withIds(List<String> ids, String proposal) {
        return "{\"findingIds\":" + idList(ids) + "," + proposal.substring(1);
    }

    private static String idList(List<String> ids) {
        return ids.stream().map(ProposalSeam1::quoted).collect(Collectors.joining(",", "[", "]"));
    }

    private static ApiClient.Response bulk(ApiClient client, String submissionId, String body) {
        return client.postJson(bulkOf(submissionId), body);
    }

    /** Applies it and expects one batch for every Finding named (201). */
    private static JsonNode applied(ApiClient client, String submissionId, List<String> ids, String proposal) {
        var response = bulk(client, submissionId, withIds(ids, proposal));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.header("cache-control")).contains("no-store");
        JsonNode batch = ApiClient.json(response);
        assertThat(batch.get("count").asInt()).isEqualTo(ids.size());
        return batch;
    }

    private static List<String> ids(Draft draft, String... identifiers) {
        List<String> ids = new ArrayList<>();
        JsonNode page = findings(draft.smo(), draft.submissionId());
        for (String identifier : identifiers) {
            ids.add(byIdentifier(page, identifier).get("id").asString());
        }
        return ids;
    }

    private static void assertNothingProposed(Draft draft) {
        findings(draft.smo(), draft.submissionId()).get("items").forEach(
                item -> assertThat(item.get("currentProposal").isNull()).as(item.get("identifier").asString()).isTrue());
    }

    @Test
    void oneStatusWithItsFieldsBecomesTheCurrentProposalOfEverySelectedFindingInOneBatch() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046", "CVE-2022-42889");

        JsonNode batch = applied(draft.smo(), draft.submissionId(), ids,
                remediationPlanned("Upgrade every log4j and commons-text jar in the next release.", LATER));

        String batchId = batch.get("batchId").asString();
        assertThat(UUID.fromString(batchId)).isNotNull();
        JsonNode page = findings(draft.smo(), draft.submissionId());
        List<String> proposalIds = new ArrayList<>();
        for (String identifier : List.of("CVE-2021-44228", "CVE-2021-45046", "CVE-2022-42889")) {
            JsonNode finding = byIdentifier(page, identifier);
            JsonNode proposal = finding.get("currentProposal");
            assertThat(proposal.get("status").asString()).as(identifier).isEqualTo("REMEDIATION_PLANNED");
            assertThat(proposal.get("plan").asString())
                    .isEqualTo("Upgrade every log4j and commons-text jar in the next release.");
            assertThat(proposal.get("targetDate").asString()).isEqualTo(LATER);
            assertThat(proposal.get("batchId").asString()).as("the proposal names its batch").isEqualTo(batchId);
            assertThat(finding.get("reviewState").asString()).isEqualTo("PENDING");
            proposalIds.add(proposal.get("id").asString());
        }
        assertThat(proposalIds).as("one proposal per Finding").doesNotHaveDuplicates();
        for (String identifier : List.of("CVE-2020-9488", "CVE-2018-0002", "GHSA-aaaa-bbbb-cccc")) {
            assertThat(byIdentifier(page, identifier).get("currentProposal").isNull()).as(identifier).isTrue();
        }
        JsonNode history = ApiClient.json(draft.smo().get(proposalsOf(ids.get(0))));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("batchId").asString()).isEqualTo(batchId);
    }

    @Test
    void eachBatchIsItsOwnAndAProposalForOneFindingHasNoBatch() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");

        String first = applied(draft.smo(), draft.submissionId(), ids, falsePositive("Not on the class path.")).get("batchId")
                .asString();
        String second = applied(draft.smo(), draft.submissionId(), ids.subList(0, 1), remediated("Upgraded."))
                .get("batchId").asString();
        JsonNode single = proposed(draft.smo(), ids.get(1), remediated("Upgraded on its own."));

        assertThat(second).isNotEqualTo(first);
        assertThat(single.get("batchId").isNull()).as("a single proposal is in no batch").isTrue();
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(byIdentifier(page, "CVE-2021-44228").get("currentProposal").get("batchId").asString()).isEqualTo(second);
        assertThat(byIdentifier(page, "CVE-2021-45046").get("currentProposal").get("batchId").isNull()).isTrue();
        JsonNode history = ApiClient.json(draft.smo().get(proposalsOf(ids.get(1))));
        assertThat(history).as("the batch's proposal is kept under the single one").hasSize(2);
        assertThat(history.get(1).get("batchId").asString()).isEqualTo(first);
    }

    @Test
    void aBatchPutsAConfirmedOrDisputedReviewBackToPending() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        for (String id : ids) {
            proposed(draft.smo(), id, falsePositive("Not reachable."));
        }
        arrange.findingReview(ids.get(0), "CONFIRMED");
        arrange.findingReview(ids.get(1), "DISPUTED");

        applied(draft.smo(), draft.submissionId(), ids, remediated("Upgraded both."));

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(byIdentifier(page, "CVE-2021-44228").get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(byIdentifier(page, "CVE-2021-45046").get("reviewState").asString()).isEqualTo("PENDING");
    }

    @Test
    void theChipsOfThePrepareScreenFollowABatch() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        applied(draft.smo(), draft.submissionId(), ids, remediated("Upgraded."));
        arrange.findingReview(ids.get(1), "DISPUTED");

        String list = findingsOf(draft.submissionId()) + "?size=500&";
        assertThat(ApiClient.json(draft.smo().get(list + "needsAttention=true")).get("total").asInt())
                .as("Needs attention: the five with no proposal and the Disputed one").isEqualTo(6);
        assertThat(ApiClient.json(draft.smo().get(list + "reviewState=DISPUTED")).get("total").asInt())
                .as("Disputed").isEqualTo(1);
        assertThat(ApiClient.json(draft.smo().get(list + "severity=CRITICAL,HIGH")).get("total").asInt())
                .as("Critical/High").isEqualTo(4);
        assertThat(ApiClient.json(draft.smo().get(list)).get("total").asInt()).as("All").isEqualTo(7);
    }

    @Test
    void theFieldsAreCheckedAsForOneFindingAndAWrongOneKeepsNothing() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        String submission = draft.submissionId();

        assertInvalidField(bulk(draft.smo(), submission, withIds(ids, "{\"status\":\"REMEDIATION_PLANNED\",\"plan\":\"p\"}")),
                "targetDate");
        assertInvalidField(bulk(draft.smo(), submission, withIds(ids, remediationPlanned("p", "2020-01-01"))), "targetDate");
        assertInvalidField(bulk(draft.smo(), submission,
                withIds(ids, "{\"status\":\"REMEDIATED\",\"evidence\":\"e\",\"plan\":\"p\"}")), "plan");
        assertInvalidField(bulk(draft.smo(), submission, withIds(ids, remediated(" ​ "))), "evidence");
        assertInvalidField(bulk(draft.smo(), submission, withIds(ids, remediated("x".repeat(4001)))), "evidence");
        assertInvalidField(bulk(draft.smo(), submission, withIds(ids, "{\"status\":\"FIXED\"}")), "status");
        assertInvalidField(bulk(draft.smo(), submission, "{\"findingIds\":" + idList(ids) + "}"), "status");
        assertThat(bulk(draft.smo(), submission, "{\"findingIds\":").status()).as("an unreadable body").isEqualTo(400);

        assertNothingProposed(draft);
    }

    @Test
    void theListOfFindingsMustBeAListOfDistinctFindingIdsOfAReasonableLength() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        String submission = draft.submissionId();
        String body = remediated("Upgraded.");

        assertInvalidField(bulk(draft.smo(), submission, body), "findingIds");
        assertInvalidField(bulk(draft.smo(), submission, withIds(List.of(), body)), "findingIds");
        assertInvalidField(bulk(draft.smo(), submission, withIds(List.of(ids.get(0), "not-an-id-" + SENTINEL), body)),
                "findingIds");
        assertInvalidField(bulk(draft.smo(), submission, "{\"findingIds\":[null]," + body.substring(1)), "findingIds");
        var twice = bulk(draft.smo(), submission, withIds(List.of(ids.get(0), ids.get(1), ids.get(0)), body));
        assertInvalidField(twice, "findingIds");
        assertThat(ApiClient.json(twice).get("detail").asString()).contains("each Finding once");
        List<String> tooMany = IntStream.range(0, 1001).mapToObj(n -> UUID.randomUUID().toString()).toList();
        var refused = bulk(draft.smo(), submission, withIds(tooMany, body));
        assertInvalidField(refused, "findingIds");
        assertThat(ApiClient.json(refused).get("detail").asString()).contains("at most 1000");
        var atTheLimit = bulk(draft.smo(), submission, withIds(tooMany.subList(0, 1000), body));
        assertInvalidField(atTheLimit, "findingIds");
        assertThat(ApiClient.json(atTheLimit).get("detail").asString()).as("1000 is allowed; these are simply not ours")
                .contains("only Findings of this VA Submission");

        assertNothingProposed(draft);
    }

    @Test
    void aFindingOfAnotherVaSubmissionOrOneThatDoesNotExistRefusesTheWholeList() {
        Draft draft = draftWithFindings();
        ApiClient smoB = signedInAs(SMO_B);
        Draft other = new Draft(smoB, idOf("SYS-F2"), started(smoB, idOf("SYS-F2")).get("id").asString());
        settledDc(other, "dc.xml", Samples.dependencyCheckSampleXml());
        List<String> ours = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        String theirs = findingId(other, "CVE-2022-42889");
        String body = remediated("Upgraded.");

        var foreign = bulk(draft.smo(), draft.submissionId(), withIds(List.of(ours.get(0), theirs, ours.get(1)), body));
        var missing = bulk(draft.smo(), draft.submissionId(), withIds(List.of(ours.get(0), MISSING), body));

        assertInvalidField(foreign, "findingIds");
        assertInvalidField(missing, "findingIds");
        assertThat(ApiClient.json(foreign).get("detail")).as("which one is never said")
                .isEqualTo(ApiClient.json(missing).get("detail"));
        assertNothingProposed(draft);
        assertNothingProposed(other);
        assertThat(entries("BATCH_PROPOSED", draft.systemId())).isEmpty();
        assertThat(entries("STATUS_PROPOSED", draft.systemId())).isEmpty();

        applied(draft.smo(), draft.submissionId(), ours, body);
    }

    @Test
    void onlyTheSmoOfTheSystemAppliesABatchAndAnyoneElseIsRefusedBeforeAnythingIsRead() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228");
        String body = withIds(ids, remediated("Upgraded."));

        for (ApiClient other : List.of(signedInAs(CM), vaPoc(), signedInAs(CM_OTHER))) {
            assertThat(bulk(other, draft.submissionId(), body).status()).as("not the SMO/Tech Lead of any System")
                    .isEqualTo(403);
        }
        var stranger = bulk(signedInAs(SMO_B), draft.submissionId(), body);
        var missing = bulk(draft.smo(), MISSING, body);
        assertNotFound(stranger);
        assertNotFound(missing);
        assertThat(ApiClient.json(stranger).get("detail")).isEqualTo(ApiClient.json(missing).get("detail"));
        assertThat(anotherBrowser().withCsrf().postJson(bulkOf(draft.submissionId()), body).status()).as("not signed in")
                .isEqualTo(401);
        assertThat(api.postJson(bulkOf(draft.submissionId()), body).status()).as("no CSRF token").isEqualTo(403);

        assertNothingProposed(draft);
        assertThat(entries("BATCH_PROPOSED", draft.systemId())).isEmpty();
        assertThat(entries("STATUS_PROPOSED", draft.systemId())).isEmpty();
    }

    @Test
    void aVaPocWhoIsAlsoTheSmoOfAnotherSystemCannotApplyABatchToThisSystem() {
        registered(
                List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-F3", "File three", POC_TWO, CM_OTHER, "Q3")),
                List.of());
        ApiClient smo = signedInAs(SMO_A);
        Draft draft = new Draft(smo, idOf("SYS-F1"), started(smo, idOf("SYS-F1")).get("id").asString());
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        assertNotFound(bulk(otherVaPoc(), draft.submissionId(), withIds(ids(draft, "CVE-2021-44228"), remediated("x"))));

        assertNothingProposed(draft);
    }

    @Test
    void aBatchNeedsADraftOrAVaSubmissionReturnedForReworkAndEachRefusalIsAudited() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");

        for (String state : List.of("IN_REVIEW", "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED",
                "WITHDRAWN", "NOT_ENDORSED")) {
            arrange.submissionState(draft.submissionId(), state);
            var refused = bulk(draft.smo(), draft.submissionId(), withIds(ids, remediated("Upgraded in " + state + ".")));
            assertThat(refused.status()).as(state).isEqualTo(409);
            assertThat(ApiClient.json(refused).get("type").asString())
                    .isEqualTo("https://annualva/problems/invalid-transition");
        }
        assertNothingProposed(draft);

        arrange.submissionState(draft.submissionId(), "RETURNED_FOR_REWORK");
        applied(draft.smo(), draft.submissionId(), ids, remediated("Upgraded after rework."));
        arrange.submissionState(draft.submissionId(), "DRAFT");
        applied(draft.smo(), draft.submissionId(), ids, remediated("Upgraded in Draft."));

        JsonNode entries = entries("BATCH_PROPOSED", draft.systemId());
        assertThat(entries).hasSize(9);
        List<JsonNode> refusals = StreamSupport.stream(entries.spliterator(), false)
                .filter(entry -> entry.get("outcome").asString().equals("REFUSED")).toList();
        assertThat(refusals).hasSize(7);
        for (JsonNode entry : refusals) {
            assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
            assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        }
    }

    @Test
    void aBatchIsOneAuditEntryWithItsIdTheCountAndTheStatusCode() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046", "CVE-2022-42889");

        String batchId = applied(draft.smo(), draft.submissionId(), ids,
                riskAccepted("Reason " + SENTINEL, "Name " + SENTINEL, "Role " + SENTINEL)).get("batchId").asString();

        JsonNode entries = entries("BATCH_PROPOSED", draft.systemId());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(entry.get("detail").propertyNames()).containsExactlyInAnyOrder("batch_id", "count", "finding_status");
        assertThat(entry.get("detail").get("batch_id").asString()).isEqualTo(batchId);
        assertThat(entry.get("detail").get("count").asInt()).isEqualTo(3);
        assertThat(entry.get("detail").get("finding_status").asString()).isEqualTo("RISK_ACCEPTED");
        JsonNode perFinding = entries("STATUS_PROPOSED", draft.systemId());
        assertThat(perFinding).as("and one entry per Finding of the batch").hasSize(3);
        List<String> audited = new ArrayList<>();
        for (JsonNode one : perFinding) {
            assertThat(one.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(one.get("submissionId").asString()).isEqualTo(draft.submissionId());
            assertThat(one.get("detail").propertyNames()).containsExactlyInAnyOrder("batch_id", "finding_status");
            assertThat(one.get("detail").get("batch_id").asString()).isEqualTo(batchId);
            assertThat(one.get("detail").get("finding_status").asString()).isEqualTo("RISK_ACCEPTED");
            audited.add(one.get("findingId").asString());
        }
        assertThat(audited).containsExactlyInAnyOrderElementsOf(ids);
        JsonNode ofOneFinding = StreamSupport.stream(perFinding.spliterator(), false)
                .filter(one -> one.get("findingId").asString().equals(ids.get(1))).findFirst().orElseThrow();
        assertThat(ofOneFinding.get("detail").get("batch_id").asString())
                .as("a search of the audit log for a Finding finds its batch-made proposal").isEqualTo(batchId);
        assertThat(entries.toString() + perFinding).doesNotContain(SENTINEL);
    }

    @Test
    void aRiskAcceptedBatchLinksOneMemoToEveryProposalAndTheMemoIsCheckedAsForOneFinding() {
        Draft draft = draftWithFindings();
        String memo = settledEvidence(draft, DEPENDENCY_CHECK, "memo.pdf", Samples.pdf()).get("id").asString();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");

        assertInvalidField(bulk(draft.smo(), draft.submissionId(), withIds(ids, riskAcceptedWithMemo("r", "n", "r", MISSING))),
                "approvalMemoFileId");
        assertNothingProposed(draft);
        applied(draft.smo(), draft.submissionId(), ids, riskAcceptedWithMemo("No fix yet.", "A. Tan", "Head of Ops", memo));

        JsonNode page = findings(draft.smo(), draft.submissionId());
        for (String identifier : List.of("CVE-2021-44228", "CVE-2021-45046")) {
            assertThat(byIdentifier(page, identifier).get("currentProposal").get("approvalMemoFileId").asString())
                    .isEqualTo(memo);
        }
        JsonNode entry = entries("BATCH_PROPOSED", draft.systemId()).get(0);
        assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(memo);
        JsonNode perFinding = entries("STATUS_PROPOSED", draft.systemId());
        assertThat(perFinding).hasSize(2);
        perFinding.forEach(one -> assertThat(one.get("detail").get("file_id").asString()).isEqualTo(memo));
    }

    @Test
    void theProgressCountsFollowABatch() {
        Draft draft = draftWithFindings();

        applied(draft.smo(), draft.submissionId(), ids(draft, "CVE-2021-44228", "CVE-2021-45046", "CVE-2022-42889"),
                remediated("Upgraded."));

        JsonNode readiness = ApiClient.json(draft.smo().get(SUBMISSIONS + "/" + draft.submissionId() + "/readiness"));
        assertThat(readiness.get("total").asInt()).isEqualTo(7);
        assertThat(readiness.get("proposed").asInt()).isEqualTo(3);
        assertThat(readiness.get("unproposed").asInt()).isEqualTo(4);
        assertThat(readiness.get("awaitingReview").asInt()).isEqualTo(3);
    }

    @Test
    void aBatchIsNeverChangedOrRemovedThroughTheApplicationsConnection() {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        String batchId = applied(draft.smo(), draft.submissionId(), ids, remediated("Upgraded.")).get("batchId").asString();

        for (String sql : new String[] {"UPDATE bulk_batch SET proposed_at = now()",
                "UPDATE bulk_batch SET proposed_at = now() WHERE id = gen_random_uuid()", "DELETE FROM bulk_batch",
                "DELETE FROM bulk_batch WHERE id = gen_random_uuid()", "TRUNCATE bulk_batch CASCADE"}) {
            assertThatThrownBy(() -> application.sql(sql).update()).as(sql).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(byIdentifier(page, "CVE-2021-44228").get("currentProposal").get("batchId").asString()).isEqualTo(batchId);
    }

    @Test
    void nothingTheSmoWroteReachesALogLineAnAuditEntryOrAnErrorBody(CapturedOutput output) {
        Draft draft = draftWithFindings();
        List<String> ids = ids(draft, "CVE-2021-44228", "CVE-2021-45046");
        String submission = draft.submissionId();

        JsonNode kept = applied(draft.smo(), submission, ids, remediationPlanned("Plan " + SENTINEL, LATER));
        List<String> bodies = List.of(
                bulk(draft.smo(), submission, withIds(ids, remediated(SENTINEL + "x".repeat(4001)))).body(),
                bulk(draft.smo(), submission, withIds(ids, "{\"status\":\"" + SENTINEL + "\"}")).body(),
                bulk(draft.smo(), submission, withIds(List.of("id-" + SENTINEL), remediated("x"))).body(),
                bulk(draft.smo(), submission, withIds(List.of(ids.get(0), MISSING), remediated(SENTINEL))).body(),
                bulk(draft.smo(), submission, "{\"findingIds\":[\"" + SENTINEL).body(),
                bulk(signedInAs(SMO_B), submission, withIds(ids, remediated(SENTINEL))).body(),
                bulk(signedInAs(CM), submission, withIds(ids, remediated(SENTINEL))).body());
        arrange.submissionState(submission, "IN_REVIEW");
        String refused = bulk(draft.smo(), submission, withIds(ids, remediated("Patched " + SENTINEL))).body();
        String audit = otherVaPoc().get("/api/audit-entries?size=200").body();

        assertThat(kept.toString()).doesNotContain(SENTINEL);
        assertThat(findings(draft.smo(), submission).toString()).as("the proposals are data, so they are returned")
                .contains(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("batch-proposed");
        assertThat(audit).as("the audit log").doesNotContain(SENTINEL).contains("BATCH_PROPOSED");
        assertThat(String.join("\n", bodies) + refused).as("error bodies").doesNotContain(SENTINEL);
    }
}
