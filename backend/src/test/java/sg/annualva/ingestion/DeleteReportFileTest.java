package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.FakeMalwareScanner;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 60 (stories 23 and 39): {@code DELETE /api/submissions/{id}/files/{fileId}} lets the SMO/Tech Lead delete a report
 * file uploaded by mistake while the VA Submission is in Draft or Returned for Rework. Its places go, the Findings it
 * reported are rated by the places they have left, and a Finding it leaves reported nowhere is removed if it never had a
 * proposal, and otherwise stays and is proposed Remediated for the Cyber Manager, as a rescan would (ADR 0029 item 5);
 * proposals and their history are never deleted. Once the Scan Report holds nothing, its mode can change again.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeleteReportFileTest extends DeleteSeam1 {

    /** alpha (Critical), bravo (High), charlie (Medium) and delta (Critical). */
    private static byte[] mistaken(String name) {
        return jsonReport(dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"),
                dependency(PKG_C, "/app/charlie.js", "CVE-2027-0003", "5.0"),
                dependency(name, "/app/delta.js", "CVE-2027-0004", "9.1"));
    }

    @Test
    void deletingAReportFileRemovesItsPlacesAndTheUnproposedFindingsItAloneReportedAndKeepsEveryProposal() {
        Draft draft = draft();
        String mistake = settledDc(draft, "mistake.json", mistaken(PKG_D)).get("id").asString();
        String libs = settledDc(draft, "libs.json", jsonReport(dependency(PKG_A, "/lib/alpha.js", "CVE-2027-0001", "2.0")))
                .get("id").asString();
        String alpha = findingId(draft, "CVE-2027-0001");
        String bravo = findingId(draft, "CVE-2027-0002");
        String charlie = findingId(draft, "CVE-2027-0003");
        String bravoProposal = proposed(draft.smo(), bravo, remediated("Upgraded bravo.")).get("id").asString();
        proposed(draft.smo(), charlie, falsePositive("Not reachable."));
        arrange.findingReview(charlie, CONFIRMED);

        fileDeleted(draft.smo(), draft.submissionId(), mistake);

        assertThat(fileIds(draft.smo(), draft.submissionId())).as("the file is no longer listed").containsExactly(libs);
        assertNotFound(draft.smo().get(fileAt(draft.submissionId(), mistake)));
        assertThat(draft.smo().download(fileAt(draft.submissionId(), mistake) + "/content").status()).isEqualTo(404);
        assertNotFound(deleteFile(draft.smo(), draft.submissionId(), mistake));

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(identifiers(draft)).as("delta never had a proposal and no file reports it: removed")
                .containsExactlyInAnyOrder("CVE-2027-0001", "CVE-2027-0002", "CVE-2027-0003");
        JsonNode kept = byIdentifier(page, "CVE-2027-0001");
        assertThat(kept.get("id").asString()).isEqualTo(alpha);
        assertThat(kept.get("occurrenceCount").asInt()).isEqualTo(1);
        assertThat(kept.get("severity").asString()).as("rated by the place it has left").isEqualTo("LOW");
        assertThat(sourceNames(kept)).containsExactly("libs.json");
        assertThat(kept.get("currentProposal").isNull()).as("still reported, so nothing is proposed").isTrue();

        JsonNode alreadyRemediated = byIdentifier(page, "CVE-2027-0002");
        assertThat(alreadyRemediated.get("occurrenceCount").asInt()).isZero();
        assertThat(alreadyRemediated.get("severity").asString()).as("as it was last reported").isEqualTo("HIGH");
        assertThat(alreadyRemediated.get("currentProposal").get("id").asString()).as("no second Remediated")
                .isEqualTo(bravoProposal);

        JsonNode gone = byIdentifier(page, "CVE-2027-0003");
        assertThat(gone.get("id").asString()).isEqualTo(charlie);
        assertThat(gone.get("occurrenceCount").asInt()).isZero();
        JsonNode proposal = gone.get("currentProposal");
        assertThat(proposal.get("status").asString()).isEqualTo("REMEDIATED");
        assertThat(proposal.get("proposedAs").asString()).isEqualTo("FILE_DELETED");
        assertThat(proposal.get("evidence").asString()).isEqualTo("Not reported by any file of this Scan Report since "
                + "the file mistake.json (uploaded 2027-08-02) was deleted on 2027-08-02.");
        assertThat(proposal.get("replacedFile").get("id").asString()).as("where it was last reported").isEqualTo(mistake);
        assertThat(proposal.get("replacedFile").get("name").asString()).isEqualTo("mistake.json");
        assertThat(gone.get("reviewState").asString()).as("for the Cyber Manager to review again").isEqualTo("PENDING");
        JsonNode history = ApiClient.json(draft.smo().get(proposalsOf(charlie)));
        assertThat(history).as("the earlier proposal is kept").hasSize(2);
        assertThat(history.get(1).get("status").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(bravo)))).hasSize(1);
    }

    @Test
    void aFindingReportedAgainAfterItsFileWasDeletedLosesTheProposalTheDeletionMade() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String charlie = findingId(draft, "CVE-2027-0003");
        proposed(draft.smo(), charlie, falsePositive("Not reachable."));
        fileDeleted(draft.smo(), draft.submissionId(), first);
        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2027-0003").get("currentProposal")
                .get("proposedAs").asString()).isEqualTo("FILE_DELETED");

        String again = settledDc(draft, "right.json",
                jsonReport(dependency(PKG_C, "/app/charlie.js", "CVE-2027-0003", "5.0"))).get("id").asString();

        JsonNode reported = finding(draft.smo(), draft.submissionId(), "CVE-2027-0003");
        assertThat(reported.get("id").asString()).isEqualTo(charlie);
        assertThat(reported.get("currentProposal").isNull()).as("the deletion's Remediated no longer holds").isTrue();
        assertThat(reported.get("reviewState").isNull()).isTrue();
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(charlie)))).as("both stay in the history").hasSize(2);
        JsonNode withdrawn = onlyEntry("PROPOSAL_WITHDRAWN", "SUCCESS", draft.systemId());
        assertThat(withdrawn.get("findingId").asString()).isEqualTo(charlie);
        assertThat(withdrawn.get("detail").get("proposed_as").asString()).isEqualTo("FILE_DELETED");
        assertThat(withdrawn.get("detail").get("file_id").asString()).isEqualTo(again);
    }

    @Test
    void onceTheScanReportHoldsNoFileAndNoFindingItsModeCanChangeAgain() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String sample = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();
        assertConflict(setMode(draft.smo(), sub, DEPENDENCY_CHECK, NO_LIBRARIES), "report-not-empty");

        fileDeleted(draft.smo(), sub, sample);

        assertThat(findings(draft.smo(), sub).get("total").asInt()).as("none had a proposal").isZero();
        assertThat(modeSet(draft.smo(), sub, DEPENDENCY_CHECK, NO_LIBRARIES).get("mode").asString())
                .isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void aFindingWithAProposalThatStaysKeepsTheModeFixed() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String sample = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();
        proposed(draft.smo(), findingId(draft, "CVE-2021-44228"), remediated("Upgraded log4j."));

        fileDeleted(draft.smo(), sub, sample);

        assertThat(identifiers(draft)).as("only the Finding with a proposal stays").containsExactly("CVE-2021-44228");
        assertConflict(setMode(draft.smo(), sub, DEPENDENCY_CHECK, NO_LIBRARIES), "report-not-empty");
    }

    private static final String NO_LIBRARIES = mode(NOT_APPLICABLE, "No libraries after all.");

    @Test
    void aFileWaitingForTheScannerCanBeDeletedAndIsNeverSent() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String waiting = uploaded(draft.smo(), sub, DEPENDENCY_CHECK, "waiting.json", firstScan()).get("id").asString();

        fileDeleted(draft.smo(), sub, waiting);
        pass();
        pass();

        assertThat(scanner.submissions()).as("a deleted file is not sent to the scanner").isZero();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("its unscanned bytes are deleted").isZero();
        assertThat(fileIds(draft.smo(), sub)).isEmpty();
        assertThat(ApiClient.json(draft.smo().get(SUBMISSIONS + "/" + sub + "/readiness")).get("blockers").toString())
                .as("nothing is still being checked").doesNotContain("files-scanning");
    }

    @Test
    void aFileWithTheScannerCanBeDeletedAndItsVerdictIsNeverSettled() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String scanning = uploaded(draft.smo(), sub, DEPENDENCY_CHECK, "scanning.json", firstScan()).get("id").asString();
        pass();

        fileDeleted(draft.smo(), sub, scanning);
        pass();
        pass();

        assertThat(findings(draft.smo(), sub).get("total").asInt()).as("its verdict is never settled").isZero();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("its unscanned bytes are deleted").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
    }

    @Test
    void aRejectedFileCanBeDeleted() {
        Draft draft = draft();
        String sub = draft.submissionId();
        scanner.nextFates(FakeMalwareScanner.Fate.BLOCKED);
        String rejected = settledDc(draft, "blocked.json", firstScan()).get("id").asString();

        fileDeleted(draft.smo(), sub, rejected);

        assertThat(fileIds(draft.smo(), sub)).isEmpty();
    }

    @Test
    void aRescanOfADeletedFileSettlesAsAnOrdinaryUploadAndADeletedRescanFreesTheFile() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String rescan = replaced(draft, first, "rescan.json", rescan()).get("id").asString();
        fileDeleted(draft.smo(), sub, rescan);
        JsonNode secondRescan = replaced(draft, first, "again.json", rescan());
        assertThat(secondRescan.get("replacesId").asString()).as("the file can be replaced again").isEqualTo(first);
        String again = secondRescan.get("id").asString();

        fileDeleted(draft.smo(), sub, first);
        pass();
        pass();

        JsonNode settled = status(draft.smo(), sub, again);
        assertThat(settled.get("status").asString()).isEqualTo("PARSED");
        assertThat(settled.get("replacesId").isNull()).as("it no longer replaces anything").isTrue();
        assertThat(fileIds(draft.smo(), sub)).containsExactly(again);
        assertThat(identifiers(draft)).as("the rescan's Findings, as an ordinary upload's")
                .containsExactlyInAnyOrder("CVE-2027-0001", "CVE-2027-0002", "CVE-2027-0004");
        assertThat(entries("FILE_REPLACED", draft.systemId())).as("nothing was replaced").isEmpty();
        fileDeleted(draft.smo(), sub, again);
        assertThat(fileIds(draft.smo(), sub)).as("and, an ordinary upload now, it can be deleted").isEmpty();
    }

    @Test
    void aParsedRescanIsDeletedLikeAnyFileThenTheFileItReplacedAndTheModeChangesAgain() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        // The same scan again: every key is kept, so no Finding gets a proposal and nothing keeps the Scan Report busy.
        String rescan = replacedAndSettled(draft, first, "rescan.json", firstScan()).get("id").asString();
        assertConflict(deleteFile(draft.smo(), sub, first), "file-not-deletable");

        fileDeleted(draft.smo(), sub, rescan);

        assertThat(identifiers(draft)).as("its Findings had no proposal and no other place: removed").isEmpty();
        JsonNode replaced = status(draft.smo(), sub, first);
        assertThat(replaced.get("supersededBy").asString()).as("the replaced file stays replaced").isEqualTo(rescan);
        assertThat(fileIds(draft.smo(), sub)).containsExactly(first);

        fileDeleted(draft.smo(), sub, first);

        assertThat(fileIds(draft.smo(), sub)).isEmpty();
        assertThat(modeSet(draft.smo(), sub, DEPENDENCY_CHECK, NO_LIBRARIES).get("mode").asString())
                .as("the Scan Report holds nothing, so its mode changes").isEqualTo(NOT_APPLICABLE);
        assertThat(entries("FILE_DELETED", draft.systemId())).as("two deletions and the one refusal").hasSize(3);
    }

    @Test
    void aFileWhoseRescanIsDeletedKeepsTheModeFixedUntilItIsDeletedToo() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String rescan = replacedAndSettled(draft, first, "rescan.json", firstScan()).get("id").asString();

        fileDeleted(draft.smo(), sub, rescan);

        assertThat(identifiers(draft)).as("no Finding is left").isEmpty();
        assertConflict(setMode(draft.smo(), sub, DEPENDENCY_CHECK, NO_LIBRARIES), "report-not-empty");
        fileDeleted(draft.smo(), sub, first);
        assertThat(modeSet(draft.smo(), sub, DEPENDENCY_CHECK, NO_LIBRARIES).get("mode").asString())
                .as("the Scan Report holds no file and no Finding now").isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void aFileWhoseRescanWasDeletedStaysReplacedAndCannotBeReplacedAgain() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String rescan = replacedAndSettled(draft, first, "rescan.json", firstScan()).get("id").asString();
        fileDeleted(draft.smo(), sub, rescan);

        assertConflict(replace(draft.smo(), sub, first, "again.json", firstScan()), "file-not-replaceable");

        assertThat(fileIds(draft.smo(), sub)).as("no rescan was kept").containsExactly(first);
    }

    @Test
    void aReplaceWhoseFileWasSupersededAndItsRescanDeletedMeanwhileIsRefusedAndKeepsNothing() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String rescan = replaced(draft, first, "rescan.json", firstScan()).get("id").asString();
        pass();
        // After the replace's first checks and before the transaction that keeps it: the live rescan settles, superseding
        // the file, and is deleted, which frees the one-live-rescan index.
        fileStore.beforeNextQuarantine(() -> {
            pass();
            fileDeleted(draft.smo(), sub, rescan);
        });

        assertConflict(replace(draft.smo(), sub, first, "late.json", firstScan()), "file-not-replaceable");

        assertThat(status(draft.smo(), sub, first).get("supersededBy").asString()).isEqualTo(rescan);
        assertThat(fileIds(draft.smo(), sub)).as("no second rescan was kept").containsExactly(first);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("its quarantined bytes are discarded").isZero();
        List<JsonNode> refused = new ArrayList<>();
        entries("FILE_UPLOADED", draft.systemId()).forEach(entry -> {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                refused.add(entry);
            }
        });
        assertThat(refused).hasSize(1);
        assertThat(refused.getFirst().get("detail").get("reason").asString()).isEqualTo("not-replaceable");
        assertThat(refused.getFirst().get("detail").get("replaced_file_id").asString()).isEqualTo(first);
    }

    @Test
    void deletingAParsedRescanProposesRemediatedForItsProposedFindingsAndRestoresNothingOfTheFileItReplaced() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String alpha = findingId(draft, "CVE-2027-0001");
        String bravo = findingId(draft, "CVE-2027-0002");
        proposed(draft.smo(), alpha, falsePositive("Only used in the build."));
        proposed(draft.smo(), bravo, remediationPlanned("Upgrade bravo.", LATER));
        String rescan = replacedAndSettled(draft, first, "rescan.json", rescan()).get("id").asString();
        assertThat(identifiers(draft)).contains("CVE-2027-0004");

        fileDeleted(draft.smo(), sub, rescan);

        JsonNode page = findings(draft.smo(), sub);
        assertThat(identifiers(draft)).as("delta never had a proposal and no file reports it: removed")
                .containsExactlyInAnyOrder("CVE-2027-0001", "CVE-2027-0002", "CVE-2027-0003");
        for (String identifier : List.of("CVE-2027-0001", "CVE-2027-0002")) {
            JsonNode finding = byIdentifier(page, identifier);
            assertThat(finding.get("occurrenceCount").asInt()).as(identifier + ": the replaced file's places stay gone")
                    .isZero();
            assertThat(finding.get("sourceFiles")).isEmpty();
            JsonNode proposal = finding.get("currentProposal");
            assertThat(proposal.get("status").asString()).isEqualTo("REMEDIATED");
            assertThat(proposal.get("proposedAs").asString()).isEqualTo("FILE_DELETED");
            assertThat(proposal.get("replacedFile").get("id").asString()).as("naming the deleted rescan")
                    .isEqualTo(rescan);
            assertThat(proposal.get("evidence").asString()).contains("the file rescan.json");
        }
        assertThat(byIdentifier(page, "CVE-2027-0003").get("currentProposal").get("proposedAs").asString())
                .as("charlie was not in the rescan, so it keeps the rescan's own proposal").isEqualTo("RESCAN");
        assertThat(status(draft.smo(), sub, first).get("supersededBy").asString()).isEqualTo(rescan);
    }

    @Test
    void aRescanSettlingAfterTheFileItReplacesWasDeletedLeavesNothingToStrand() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String rescan = replaced(draft, first, "rescan.json", rescan()).get("id").asString();
        pass();

        fileDeleted(draft.smo(), sub, first);
        pass();

        assertThat(status(draft.smo(), sub, rescan).get("status").asString()).isEqualTo("PARSED");
        fileDeleted(draft.smo(), sub, rescan);
        assertThat(fileIds(draft.smo(), sub)).isEmpty();
        assertThat(identifiers(draft)).isEmpty();
    }

    @Test
    void aSupersededFileOrSupportingEvidenceIsNotDeleted() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        replacedAndSettled(draft, first, "rescan.json", rescan());
        String evidence = settledEvidence(draft, DEPENDENCY_CHECK, "report.pdf", Samples.pdf()).get("id").asString();

        assertConflict(deleteFile(draft.smo(), sub, first), "file-not-deletable");
        assertConflict(deleteFile(draft.smo(), sub, evidence), "file-not-deletable");

        assertThat(fileIds(draft.smo(), sub)).hasSize(3);
        JsonNode refusals = entries("FILE_DELETED", draft.systemId());
        assertThat(refusals).hasSize(2);
        for (JsonNode entry : refusals) {
            assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
            assertThat(entry.get("detail").get("reason").asString()).isEqualTo("not-deletable");
        }
    }

    @Test
    void onlyTheSmoOfTheSystemDeletesAndAReturnedForReworkVaSubmissionStillTakesIt() {
        Draft draft = returnedForRework();
        String sub = draft.submissionId();
        String file = fileIds(draft.smo(), sub).getFirst();
        String randomId = UUID.randomUUID().toString();

        assertNotFound(deleteFile(signedInAs(SMO_B), sub, file));
        assertNotFound(deleteFile(draft.smo(), sub, randomId));
        assertThat(deleteFile(signedInAs(CM), sub, file).status()).isEqualTo(403);
        assertThat(deleteFile(vaPoc(), sub, file).status()).isEqualTo(403);

        fileDeleted(draft.smo(), sub, file);
        JsonNode disputed = finding(draft.smo(), sub, "CVE-2027-0001");
        assertThat(disputed.get("currentProposal").get("proposedAs").asString())
                .as("its history is kept and it is proposed Remediated").isEqualTo("FILE_DELETED");
        assertThat(disputed.get("reviewState").asString()).isEqualTo("PENDING");
    }

    @Test
    void aVaSubmissionInReviewKeepsItsFilesAndTheRefusalIsAudited() {
        Draft inReview = inReview();
        String reviewed = fileIds(inReview.smo(), inReview.submissionId()).getFirst();
        ApiClient.Response refused = deleteFile(inReview.smo(), inReview.submissionId(), reviewed);
        assertConflict(refused, "invalid-transition");
        assertThat(fileIds(inReview.smo(), inReview.submissionId())).contains(reviewed);
        JsonNode entry = onlyEntry("FILE_DELETED", "REFUSED", inReview.systemId());
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
        assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(reviewed);
    }

    @Test
    void theDeletionIsAuditedWithIdsAndFixedCodesOnly(CapturedOutput output) {
        Draft draft = draft();
        String name = SENTINEL + ".json";
        String file = settledDc(draft, name, mistaken("pkg:npm/" + SENTINEL + "@1.0.0")).get("id").asString();
        String charlie = findingId(draft, "CVE-2027-0003");
        String delta = findingId(draft, "CVE-2027-0004");
        proposed(draft.smo(), charlie, falsePositive("Not reachable."));

        fileDeleted(draft.smo(), draft.submissionId(), file);

        String smoId = userId(draft.smo());
        JsonNode deleted = onlyEntry("FILE_DELETED", "SUCCESS", draft.systemId());
        assertThat(deleted.get("actorId").asString()).isEqualTo(smoId);
        assertThat(deleted.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(deleted.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(deleted.get("detail").get("file_id").asString()).isEqualTo(file);
        assertThat(deleted.get("detail").get("report_type").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(deleted.get("detail").get("count").asInt()).as("Findings removed").isEqualTo(3);

        JsonNode removed = entries("FINDING_DELETED", draft.systemId());
        assertThat(removed).hasSize(3);
        List<String> removedIds = new ArrayList<>();
        for (JsonNode entry : removed) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("actorId").asString()).isEqualTo(smoId);
            assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(file);
            removedIds.add(entry.get("findingId").asString());
        }
        assertThat(removedIds).contains(delta).doesNotContain(charlie);

        List<JsonNode> byDeletion = new ArrayList<>();
        entries("STATUS_PROPOSED", draft.systemId()).forEach(entry -> {
            if (entry.get("detail").get("proposed_as").asString().equals("FILE_DELETED")) {
                byDeletion.add(entry);
            }
        });
        assertThat(byDeletion).hasSize(1);
        JsonNode proposedEntry = byDeletion.getFirst();
        assertThat(proposedEntry.get("findingId").asString()).isEqualTo(charlie);
        assertThat(proposedEntry.get("actorId").asString()).isEqualTo(smoId);
        assertThat(proposedEntry.get("detail").get("proposed_as").asString()).isEqualTo("FILE_DELETED");
        assertThat(proposedEntry.get("detail").get("finding_status").asString()).isEqualTo("REMEDIATED");
        assertThat(proposedEntry.get("detail").get("replaced_file_id").asString()).isEqualTo(file);

        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2027-0003").get("currentProposal").get("evidence")
                .asString()).as("the name is data in the proposal").contains(SENTINEL);
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }
}
