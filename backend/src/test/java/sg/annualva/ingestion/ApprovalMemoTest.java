package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 35 (ticket 19, ADR 0026 and 0027): a Risk Accepted proposal may carry an approval memo, which is a supporting
 * evidence file uploaded through the evidence path of the same VA Submission. The proposal links it by id; the file is
 * quarantined, scanned, type-checked and downloaded exactly as every evidence file is, and the link is refused for anything
 * else: a file that has not finished its checks, a rejected one, a machine-readable report, another VA Submission's file, or
 * one that does not exist.
 */
class ApprovalMemoTest extends ProposalSeam1 {

    private static final String BASE = "CVE-2021-44228";

    private String readyMemo(Draft draft, String name, byte[] content) {
        JsonNode file = settledEvidence(draft, DEPENDENCY_CHECK, name, content);
        assertThat(file.get("status").asString()).isEqualTo("READY");
        return file.get("id").asString();
    }

    @Test
    void aRiskAcceptedProposalLinksAMemoThatHasFinishedItsChecksAndEveryRoleCanDownloadIt() {
        Draft draft = draftWithFindings();
        String memo = readyMemo(draft, "memo.pdf", Samples.pdf());
        String id = findingId(draft, BASE);

        JsonNode proposal = proposed(draft.smo(), id, riskAcceptedWithMemo("No fix yet.", "A. Tan", "Head of Ops", memo));

        assertThat(proposal.get("approvalMemoFileId").asString()).isEqualTo(memo);
        assertThat(finding(draft.smo(), draft.submissionId(), BASE).get("currentProposal").get("approvalMemoFileId").asString())
                .isEqualTo(memo);
        for (ApiClient reader : List.of(draft.smo(), signedInAs(CM), vaPoc())) {
            var download = reader.download(fileAt(draft.submissionId(), memo) + "/content");
            assertThat(download.status()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(Samples.pdf());
            assertThat(download.header("content-disposition")).startsWith("attachment");
            assertThat(download.header("x-content-type-options")).isEqualTo("nosniff");
        }
    }

    @Test
    void aMemoCanBeAnHtmlReportToo() {
        Draft draft = draftWithFindings();
        String memo = readyMemo(draft, "memo.html", Samples.html());

        JsonNode proposal = proposed(draft.smo(), findingId(draft, BASE), riskAcceptedWithMemo("r", "n", "r", memo));

        assertThat(proposal.get("approvalMemoFileId").asString()).isEqualTo(memo);
    }

    @Test
    void oneMemoCanBeLinkedFromSeveralProposalsAndAProposalWithoutOneIsFine() {
        Draft draft = draftWithFindings();
        String memo = readyMemo(draft, "memo.pdf", Samples.pdf());

        proposed(draft.smo(), findingId(draft, BASE), riskAcceptedWithMemo("r", "n", "r", memo));
        proposed(draft.smo(), findingId(draft, "CVE-2021-45046"), riskAcceptedWithMemo("r", "n", "r", memo));
        proposed(draft.smo(), findingId(draft, "CVE-2022-42889"), riskAccepted("r", "n", "r"));

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(byIdentifier(page, BASE).get("currentProposal").get("approvalMemoFileId").asString()).isEqualTo(memo);
        assertThat(byIdentifier(page, "CVE-2021-45046").get("currentProposal").get("approvalMemoFileId").asString())
                .isEqualTo(memo);
        assertThat(byIdentifier(page, "CVE-2022-42889").get("currentProposal").get("approvalMemoFileId").isNull()).isTrue();
    }

    @Test
    void aMemoThatIsNotAFinishedEvidenceFileOfThisVaSubmissionIsRefusedAndNamed() {
        Draft draft = draftWithFindings();
        String id = findingId(draft, BASE);
        String rejected = settledEvidence(draft, DEPENDENCY_CHECK, "bad.pdf", Samples.png()).get("id").asString();
        String report = findings(draft.smo(), draft.submissionId()).get("items").get(0).get("sourceFiles").get(0).get("id")
                .asString();
        String foreign = settledEvidence(otherDraft(), DEPENDENCY_CHECK, "other.pdf", Samples.pdf()).get("id").asString();
        // Uploaded last: every pass of the scan job moves on every file that waits.
        String stillScanning = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "wait.pdf", Samples.pdf())
                .get("id").asString();

        assertThat(status(draft.smo(), draft.submissionId(), stillScanning).get("status").asString()).isEqualTo("QUARANTINED");
        assertThat(status(draft.smo(), draft.submissionId(), rejected).get("status").asString()).isEqualTo("REJECTED_TYPE");
        assertThat(status(draft.smo(), draft.submissionId(), report).get("status").asString())
                .as("a machine-readable report").isEqualTo("PARSED");
        for (String memo : List.of(stillScanning, rejected, report, foreign, "00000000-0000-4000-8000-000000000000")) {
            assertInvalidField(propose(draft.smo(), id, riskAcceptedWithMemo("r", "n", "r", memo)), "approvalMemoFileId");
        }
        assertThat(finding(draft.smo(), draft.submissionId(), BASE).get("currentProposal").isNull())
                .as("nothing was kept").isTrue();

        pass();
        pass();
        assertThat(status(draft.smo(), draft.submissionId(), stillScanning).get("status").asString()).isEqualTo("READY");
        assertThat(propose(draft.smo(), id, riskAcceptedWithMemo("r", "n", "r", stillScanning)).status())
                .as("once it has finished its checks").isEqualTo(201);
    }

    @Test
    void aMemoIsRefusedWithAnyStatusButRiskAccepted() {
        Draft draft = draftWithFindings();
        String memo = readyMemo(draft, "memo.pdf", Samples.pdf());
        String id = findingId(draft, BASE);

        var response = propose(draft.smo(), id, "{\"status\":\"REMEDIATED\",\"evidence\":\"Patched.\",\"approvalMemoFileId\":\""
                + memo + "\"}");

        assertInvalidField(response, "approvalMemoFileId");
    }

    @Test
    void theAuditEntryNamesTheMemoFileAndTheStatusAndNothingElse() {
        Draft draft = draftWithFindings();
        String memo = readyMemo(draft, "memo.pdf", Samples.pdf());

        proposed(draft.smo(), findingId(draft, BASE), riskAcceptedWithMemo("Reason " + SENTINEL, "n", "r", memo));
        proposed(draft.smo(), findingId(draft, "CVE-2021-45046"), riskAccepted("r", "n", "r"));

        JsonNode entries = entries("STATUS_PROPOSED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("detail").propertyNames()).containsExactly("finding_status");
        assertThat(entries.get(1).get("detail").propertyNames()).containsExactlyInAnyOrder("finding_status", "file_id");
        assertThat(entries.get(1).get("detail").get("file_id").asString()).isEqualTo(memo);
        assertThat(entries.toString()).doesNotContain(SENTINEL);
    }

    /** SYS-F2's VA Submission, started by its own SMO/Tech Lead. */
    private Draft otherDraft() {
        ApiClient smo = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        return new Draft(smo, system, started(smo, system).get("id").asString());
    }
}
