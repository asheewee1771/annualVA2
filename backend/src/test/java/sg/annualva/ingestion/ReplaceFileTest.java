package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.FakeMalwareScanner;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 39 (ticket 21): {@code POST /api/submissions/{id}/files/{fileId}/replace} takes a rescan of one parsed report file.
 * The rescan goes through the whole pipeline like any upload, and only once it is parsed is it compared with the file it
 * replaces, by identity key and against that file's places only: a key the rescan still reports keeps its Finding, its
 * proposals and its review, with the rescan's places instead of the old file's; a key it no longer reports loses the old
 * file's places, and a Finding that is then reported nowhere gets a Remediated proposal made by the rescan, naming it and
 * its date, for the Cyber Manager to review; a new key is a new Finding. The old file is kept, marked superseded. A rescan
 * that is rejected, or whose settlement fails, changes nothing.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReplaceFileTest extends ReplaceSeam1 {

    @Test
    void aRescanKeepsTheFindingsItStillReportsAddsNewOnesAndProposesRemediatedForTheOnesGone() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String alpha = findingId(draft, "CVE-2027-0001");
        String bravo = findingId(draft, "CVE-2027-0002");
        String charlie = findingId(draft, "CVE-2027-0003");
        String alphaProposal = proposed(draft.smo(), alpha, falsePositive("Only used in the build.")).get("id").asString();
        arrange.findingReview(alpha, "CONFIRMED");
        proposed(draft.smo(), bravo, remediationPlanned("Upgrade bravo.", LATER));
        proposed(draft.smo(), charlie, falsePositive("Not reachable."));
        arrange.findingReview(charlie, "CONFIRMED");

        JsonNode rescanFile = replacedAndSettled(draft, first, "rescan.json", rescan());

        assertThat(rescanFile.get("status").asString()).isEqualTo("PARSED");
        assertThat(rescanFile.get("replacesId").asString()).isEqualTo(first);
        assertThat(rescanFile.get("supersededBy").isNull()).isTrue();
        JsonNode old = status(draft.smo(), draft.submissionId(), first);
        assertThat(old.get("status").asString()).isEqualTo("PARSED");
        assertThat(old.get("supersededBy").asString()).as("the old file is kept, superseded")
                .isEqualTo(rescanFile.get("id").asString());
        assertThat(draft.smo().download(fileAt(draft.submissionId(), first) + "/content").status())
                .as("and can still be downloaded").isEqualTo(200);

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(4);
        JsonNode kept = byIdentifier(page, "CVE-2027-0001");
        assertThat(kept.get("id").asString()).as("the same Finding").isEqualTo(alpha);
        assertThat(kept.get("currentProposal").get("id").asString()).as("its proposal is kept").isEqualTo(alphaProposal);
        assertThat(kept.get("reviewState").asString()).as("and its review").isEqualTo("CONFIRMED");
        assertThat(kept.get("severity").asString()).as("rated by its places now: the rescan's").isEqualTo("MEDIUM");
        assertThat(kept.get("occurrenceCount").asInt()).isEqualTo(1);
        assertThat(sourceNames(kept)).containsExactly("rescan.json");
        JsonNode planned = byIdentifier(page, "CVE-2027-0002");
        assertThat(planned.get("currentProposal").get("status").asString()).isEqualTo("REMEDIATION_PLANNED");
        assertThat(planned.get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(planned.get("severity").asString()).isEqualTo("HIGH");

        JsonNode gone = byIdentifier(page, "CVE-2027-0003");
        assertThat(gone.get("id").asString()).isEqualTo(charlie);
        assertThat(gone.get("occurrenceCount").asInt()).as("reported nowhere now").isZero();
        assertThat(gone.get("sourceFiles")).isEmpty();
        assertThat(gone.get("severity").asString()).as("as it was last reported").isEqualTo("MEDIUM");
        JsonNode rescanProposal = gone.get("currentProposal");
        assertThat(rescanProposal.get("status").asString()).isEqualTo("REMEDIATED");
        assertThat(rescanProposal.get("proposedAs").asString()).isEqualTo("RESCAN");
        assertThat(rescanProposal.get("evidence").asString()).isEqualTo("Not reported by the rescan rescan.json (scanned "
                + "2027-07-20, uploaded 2027-08-02), nor by any other file of this Scan Report.");
        assertThat(rescanProposal.get("replacedFile").get("id").asString()).as("where it was last reported").isEqualTo(first);
        assertThat(rescanProposal.get("replacedFile").get("name").asString()).isEqualTo("first.json");
        assertThat(rescanProposal.get("batchId").isNull()).isTrue();
        assertThat(gone.get("reviewState").asString()).as("for the Cyber Manager to review again").isEqualTo("PENDING");
        JsonNode history = ApiClient.json(draft.smo().get(proposalsOf(charlie)));
        assertThat(history).as("the earlier proposal is kept").hasSize(2);
        assertThat(history.get(1).get("status").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(history.get(1).get("proposedAs").asString()).isEqualTo("SMO");
        assertThat(history.get(1).get("replacedFile").isNull()).isTrue();
        assertThat(history.get(0).get("replacedFile").get("id").asString()).isEqualTo(first);

        JsonNode added = byIdentifier(page, "CVE-2027-0004");
        assertThat(added.get("currentProposal").isNull()).as("a new Finding").isTrue();
        assertThat(added.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(sourceNames(added)).containsExactly("rescan.json");
    }

    @Test
    void aRescanWhoseReportGivesNoScanDateNamesOnlyTheUploadDate() {
        Draft draft = draft();
        String first = firstScanOf(draft);

        replacedAndSettled(draft, first, "undated.json",
                jsonReportOf("{\"name\":\"throwaway-project\"}", dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8")));

        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2027-0003").get("currentProposal").get("evidence")
                .asString()).isEqualTo("Not reported by the rescan undated.json (uploaded 2027-08-02), nor by any other "
                        + "file of this Scan Report.");
    }

    @Test
    void aFindingEmptiedByARescanAndReportedAgainLowerIsRatedByItsPlacesNotByWhatItWas() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String charlie = findingId(draft, "CVE-2027-0003");
        proposed(draft.smo(), charlie, remediated("Upgraded charlie."));
        replacedAndSettled(draft, first, "rescan.json", rescan());
        assertThat(state(draft)).as("no places, last reported Medium")
                .containsEntry("dc|CVE-2027-0003|" + PKG_C, "MEDIUM/0/REMEDIATED");

        settledDc(draft, "libs.json", jsonReport(dependency(PKG_C, "/lib/charlie.js", "CVE-2027-0003", "2.0")));

        assertThat(state(draft)).as("the highest of its places now, not the old Medium")
                .containsEntry("dc|CVE-2027-0003|" + PKG_C, "LOW/1/REMEDIATED");
    }

    @Test
    void aFindingWhoseCurrentProposalIsAlreadyRemediatedIsLeftAsItIs() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String charlie = findingId(draft, "CVE-2027-0003");
        String proposal = proposed(draft.smo(), charlie, remediated("Upgraded charlie.")).get("id").asString();
        arrange.findingReview(charlie, "CONFIRMED");

        replacedAndSettled(draft, first, "rescan.json", rescan());

        JsonNode gone = finding(draft.smo(), draft.submissionId(), "CVE-2027-0003");
        assertThat(gone.get("occurrenceCount").asInt()).isZero();
        assertThat(gone.get("currentProposal").get("id").asString()).as("no second Remediated").isEqualTo(proposal);
        assertThat(gone.get("reviewState").asString()).as("nothing new to review").isEqualTo("CONFIRMED");
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(charlie)))).hasSize(1);
        assertThat(entries("STATUS_PROPOSED", draft.systemId())).as("only the SMO's own proposal").hasSize(1);
    }

    @Test
    void aRescanOfTheRescanThatReportsAGoneFindingAgainTakesAwayTheRescansProposal(CapturedOutput output) {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String charlie = findingId(draft, "CVE-2027-0003");
        String rescanId = replacedAndSettled(draft, first, "rescan.json", rescan()).get("id").asString();
        assertThat(state(draft)).containsEntry("dc|CVE-2027-0003|" + PKG_C, "MEDIUM/0/REMEDIATED");
        arrange.findingReview(charlie, "CONFIRMED");

        String again = replacedAndSettled(draft, rescanId, SENTINEL + "-2.json", jsonReport(
                dependency(PKG_A, "/srv/alpha.js", "CVE-2027-0001", "5.0"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"),
                dependency(PKG_C, "/app/charlie.js", "CVE-2027-0003", "2.0"),
                dependency(PKG_D, "/app/delta.js", "CVE-2027-0004", "9.1"))).get("id").asString();

        assertReportedAgain(draft, charlie, again, output);
    }

    @Test
    void anotherFileOfTheScanReportThatReportsAGoneFindingAgainTakesAwayTheRescansProposal(CapturedOutput output) {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String charlie = findingId(draft, "CVE-2027-0003");
        replacedAndSettled(draft, first, "rescan.json", rescan());

        String again = settledDc(draft, SENTINEL + ".json",
                jsonReport(dependency(PKG_C, "/lib/charlie.js", "CVE-2027-0003", "2.0"))).get("id").asString();

        assertReportedAgain(draft, charlie, again, output);
    }

    /**
     * The rescan's Remediated is no longer current, its history is kept, the Finding needs attention again, and the
     * withdrawal is one entry by the SMO whose upload reported it again, with ids and fixed codes only.
     */
    private void assertReportedAgain(Draft draft, String charlie, String againFile, CapturedOutput output) {
        JsonNode again = finding(draft.smo(), draft.submissionId(), "CVE-2027-0003");
        assertThat(again.get("id").asString()).isEqualTo(charlie);
        assertThat(again.get("currentProposal").isNull()).as("the rescan's Remediated no longer holds").isTrue();
        assertThat(again.get("reviewState").isNull()).isTrue();
        assertThat(again.get("occurrenceCount").asInt()).isEqualTo(1);
        assertThat(again.get("severity").asString()).as("rated by the place it has now: lower").isEqualTo("LOW");
        JsonNode history = ApiClient.json(draft.smo().get(proposalsOf(charlie)));
        assertThat(history).as("the proposal stays in the history").hasSize(1);
        assertThat(history.get(0).get("proposedAs").asString()).isEqualTo("RESCAN");
        JsonNode attention = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?needsAttention=true"));
        List<String> ids = new ArrayList<>();
        attention.get("items").forEach(item -> ids.add(item.get("id").asString()));
        assertThat(ids).as("under Needs attention").contains(charlie);

        JsonNode withdrawn = entries("PROPOSAL_WITHDRAWN", draft.systemId());
        assertThat(withdrawn).hasSize(1);
        JsonNode entry = withdrawn.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(draft.smo()));
        assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(entry.get("findingId").asString()).isEqualTo(charlie);
        assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(againFile);
        assertThat(entry.get("detail").get("proposed_as").asString()).isEqualTo("RESCAN");
        assertThat(entry.get("detail").propertyNames()).containsExactlyInAnyOrder("file_id", "proposed_as");
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        assertThat(otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body()).doesNotContain(SENTINEL)
                .contains("PROPOSAL_WITHDRAWN");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void aFindingThatAnotherFileStillReportsKeepsThatPlaceAndItsStatus() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        settledDc(draft, "libs.json", jsonReport(dependency(PKG_C, "/lib/charlie.js", "CVE-2027-0003", "5.0")));
        String charlie = findingId(draft, "CVE-2027-0003");
        proposed(draft.smo(), charlie, falsePositive("Not reachable."));

        replacedAndSettled(draft, first, "rescan.json",
                jsonReport(dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8")));

        assertThat(state(draft)).containsExactly(
                Map.entry("dc|CVE-2027-0001|" + PKG_A, "CRITICAL/1/-"),
                Map.entry("dc|CVE-2027-0002|" + PKG_B, "HIGH/0/REMEDIATED"),
                Map.entry("dc|CVE-2027-0003|" + PKG_C, "MEDIUM/1/FALSE_POSITIVE"));
        assertThat(sourceNames(finding(draft.smo(), draft.submissionId(), "CVE-2027-0003"))).containsExactly("libs.json");
    }

    @Test
    void aRejectedRescanChangesNothingAndTheFileCanBeReplacedAgain() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        Map<String, String> before = state(draft);
        scanner.nextFates(FakeMalwareScanner.Fate.BLOCKED);

        JsonNode rejected = replacedAndSettled(draft, first, "rescan.json", rescan());

        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED_MALWARE");
        assertThat(status(draft.smo(), draft.submissionId(), first).get("supersededBy").isNull()).isTrue();
        assertThat(state(draft)).isEqualTo(before);

        JsonNode second = replacedAndSettled(draft, first, "rescan-again.json", rescan());
        assertThat(second.get("status").asString()).isEqualTo("PARSED");
        assertThat(status(draft.smo(), draft.submissionId(), first).get("supersededBy").asString())
                .isEqualTo(second.get("id").asString());
    }

    @Test
    void aRescanWhoseSettlementFailsLeavesTheOldFileAndTheFindingsAsTheyWereAndIsTriedAgain() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        Map<String, String> before = state(draft);
        arrange.findingFault("on");

        JsonNode failed = replacedAndSettled(draft, first, "rescan.json", rescan());

        assertThat(failed.get("status").asString()).as("still with the scanner").isEqualTo("SCAN_SUBMITTED");
        assertThat(status(draft.smo(), draft.submissionId(), first).get("supersededBy").isNull()).isTrue();
        assertThat(state(draft)).isEqualTo(before);

        arrange.findingFault("off");
        pass();
        assertThat(status(draft.smo(), draft.submissionId(), failed.get("id").asString()).get("status").asString())
                .isEqualTo("PARSED");
        assertThat(state(draft)).containsEntry("dc|CVE-2027-0003|" + PKG_C, "MEDIUM/0/REMEDIATED");
    }

    @Test
    void aRescanTakesTheOldFilesPlaceInTheScanReportsTwentyFiles() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        for (int n = 1; n < ReportFiles.MAX_FILES_PER_REPORT - 1; n++) {
            uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "f" + n + ".xml", Samples.dependencyCheckXml());
        }
        String rescanId = replaced(draft, first, "rescan.json", rescan()).get("id").asString();
        assertThat(upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "more.xml",
                Samples.dependencyCheckXml()).status()).as("the rescan on its way counts").isEqualTo(409);

        for (int n = 0; n < 6; n++) {
            pass();
        }
        assertThat(status(draft.smo(), draft.submissionId(), rescanId).get("status").asString()).isEqualTo("PARSED");
        assertThat(upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "more.xml",
                Samples.dependencyCheckXml()).status()).as("the superseded file no longer counts").isEqualTo(202);

        var full = replace(draft.smo(), draft.submissionId(), rescanId, "rescan-2.json", rescan());
        assertThat(full.status()).as("a full Scan Report still takes a rescan: %s", full.body()).isEqualTo(202);
    }
}
