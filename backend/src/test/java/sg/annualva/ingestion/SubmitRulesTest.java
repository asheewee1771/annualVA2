package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 40 and the rules submitting inherits (stories 23, 26 and 39; tickets 21 to 23): a submit is refused with a 409
 * {@code not-ready} problem that lists every reason, each with a fixed sentence and, where files are the reason, the files
 * by name, while any Finding lacks a proposed status, a Scan Report is not provided, a report file is more than 90 days
 * old, has no scan date or is dated after today, a file is still being checked, or the Declaration is not accepted. The
 * prepare screen reads the same reasons (apart from the Declaration, which is ticked on the screen) from the readiness
 * check. A refusal changes nothing, is audited with a fixed reason and publishes no event.
 */
class SubmitRulesTest extends SubmitSeam1 {

    @Test
    void anEmptyDraftNamesEveryScanReportAndTheDeclaration() {
        Draft draft = draft();

        JsonNode reasons = refusalReasons(submit(draft.smo(), draft.submissionId(), declaration(null, null)));

        assertThat(codes(reasons)).containsExactly("report-missing", "report-missing", "report-missing",
                "declaration-not-accepted");
        assertThat(reasons.get(0).get("reportType").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(reasons.get(1).get("reportType").asString()).isEqualTo(SECURE_CONFIGURATION);
        assertThat(reasons.get(2).get("reportType").asString()).isEqualTo(BURP_SUITE);
        assertThat(reasons.get(1).get("message").asString()).contains("Secure Configuration").contains("always required");
        assertThat(reasons.get(0).get("message").asString()).contains("not applicable");
        assertThat(reasons.get(3).get("reportType").isNull()).isTrue();
        assertThat(reasons.get(3).get("files")).isEmpty();
        assertThat(codes(blockers(draft.smo(), draft.submissionId())))
                .as("the screen reads the same reasons, apart from the Declaration ticked on it")
                .containsExactly("report-missing", "report-missing", "report-missing");
    }

    @Test
    void aRefusalChangesNothingIsAuditedWithAFixedReasonAndPublishesNothing() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();

        var notTicked = submit(draft.smo(), sub, declaration(false, currentVersion(draft.smo())));

        assertThat(codes(refusalReasons(notTicked))).containsExactly("declaration-not-accepted");
        JsonNode kept = submission(draft.smo(), sub);
        assertThat(kept.get("state").asString()).isEqualTo("DRAFT");
        assertThat(kept.get("declaration").isNull()).isTrue();
        assertThat(kept.get("submittedAt").isNull()).isTrue();
        JsonNode entries = entries("SUBMISSION_SUBMITTED", draft.systemId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("not-ready");
        assertThat(entries.get(0).get("detail").get("count").asInt()).isEqualTo(1);
        assertThat(events.published()).isEmpty();
    }

    @Test
    void aDeclarationVersionOtherThanTheCurrentOneIsNotAccepted() {
        Draft draft = readyDraft();

        JsonNode reasons = refusalReasons(submit(draft.smo(), draft.submissionId(), declaration(true, 99)));
        JsonNode noVersion = refusalReasons(submit(draft.smo(), draft.submissionId(), declaration(true, null)));

        assertThat(codes(reasons)).containsExactly("declaration-changed");
        assertThat(reason(reasons, "declaration-changed").get("message").asString()).contains("read it again");
        assertThat(codes(noVersion)).containsExactly("declaration-changed");
    }

    @Test
    void everyFindingNeedsAProposedStatus() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Telnet enabled", "SC-TEL-1", "telnet", "MEDIUM"));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Root login", "SC-ROOT-1", "root-ssh", "HIGH"));

        JsonNode reasons = refusalReasons(submitAccepting(draft.smo(), sub));

        assertThat(codes(reasons)).containsExactly("findings-unproposed");
        assertThat(reasons.get(0).get("message").asString()).contains("2 Findings");
        proposed(draft.smo(), unproposed(draft).get(0), falsePositive("Not reachable in this System."));
        JsonNode one = blockers(draft.smo(), sub);
        assertThat(codes(one)).as("one Finding without a proposal is enough").containsExactly("findings-unproposed");
        assertThat(one.get(0).get("message").asString()).contains("1 Finding has");
        proposeEvery(draft);
        assertThat(blockers(draft.smo(), sub)).isEmpty();
    }

    @Test
    void aScanReportUploadedButWithNoFileReadIsNotProvidedWhateverItsRejectedFilesAndEvidence() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        Draft fresh = new Draft(draft.smo(), draft.systemId(), sub);
        settledEvidence(fresh, BURP_SUITE, "memo.pdf", Samples.pdf());
        JsonNode rejected = settled(fresh, DEPENDENCY_CHECK, "page.html", Samples.html());
        assertThat(rejected.get("status").asString()).startsWith("REJECTED_");

        assertThat(blockers(draft.smo(), sub)).as("the parsed Dependency-Check file still counts").isEmpty();
        Draft other = otherDraft();
        settled(other, SECURE_CONFIGURATION, "page.html", Samples.html());
        settledEvidence(other, SECURE_CONFIGURATION, "memo.pdf", Samples.pdf());
        modeSet(other.smo(), other.submissionId(), DEPENDENCY_CHECK, mode(MANUAL, "PDF only."));

        JsonNode reasons = blockers(other.smo(), other.submissionId());

        assertThat(codes(reasons)).containsExactly("report-missing", "report-missing");
        assertThat(reasons.get(0).get("reportType").asString()).isEqualTo(SECURE_CONFIGURATION);
        assertThat(reasons.get(1).get("reportType").asString()).isEqualTo(BURP_SUITE);
    }

    @Test
    void aFileStillBeingCheckedBlocksSubmittingEvidenceAndARescanIncluded() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        String dc = ApiClient.json(draft.smo().get(filesOf(sub))).get(0).get("id").asString();
        String rescan = replaced(draft, dc, "rescan.json", dcReportAt(SCANNED)).get("id").asString();
        JsonNode one = blockers(draft.smo(), sub);
        assertThat(codes(one)).as("one file being checked is enough").containsExactly("files-scanning");
        assertThat(named(one.get(0).get("files"))).containsExactly("rescan.json (DEPENDENCY_CHECK)");
        String memo = uploadedEvidence(draft.smo(), sub, BURP_SUITE, "memo.pdf", Samples.pdf()).get("id").asString();

        JsonNode reasons = refusalReasons(submitAccepting(draft.smo(), sub));

        assertThat(codes(reasons)).containsExactly("files-scanning");
        JsonNode files = reason(reasons, "files-scanning").get("files");
        assertThat(named(files)).containsExactly("rescan.json (DEPENDENCY_CHECK)", "memo.pdf (BURP_SUITE)");
        assertThat(files.get(0).get("fileId").asString()).isEqualTo(rescan);
        assertThat(files.get(1).get("fileId").asString()).isEqualTo(memo);
        pass();
        pass();
        proposeEvery(draft);
        assertThat(blockers(draft.smo(), sub)).as("both checked and read").isEmpty();
    }

    @Test
    void aReportFileMoreThan90DaysOldIsNamedAndOneWithNoScanDateToo() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        modeSet(draft.smo(), sub, BURP_SUITE, mode(UPLOAD, null));
        settled(draft, BURP_SUITE, "burp.xml", burpExportAt("Tue Jul 20 10:00:00 SGT 2027", BURP_ISSUE));
        proposeEvery(draft);

        JsonNode undated = refusalReasons(submitAccepting(draft.smo(), sub));
        assertThat(codes(undated)).containsExactly("files-undated");
        assertThat(named(reason(undated, "files-undated").get("files"))).containsExactly("burp.xml (BURP_SUITE)");

        today("2027-10-19");
        ApiClient smo = signedInAs(SMO_A);
        JsonNode reasons = refusalReasons(submitAccepting(smo, sub));

        assertThat(codes(reasons)).containsExactly("files-too-old", "files-undated");
        assertThat(named(reason(reasons, "files-too-old").get("files"))).containsExactly("dc.json (DEPENDENCY_CHECK)");
        assertThat(reason(reasons, "files-too-old").get("message").asString()).contains("90 days");
        assertThat(codes(blockers(smo, sub))).containsExactly("files-too-old", "files-undated");
    }

    @Test
    void aReportFileDatedAfterTodayBlocksSubmitting() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        // The clock is put back before the scan the report names: the pipeline accepted it, today it is in the future.
        clock.set(Instant.parse("2027-07-15T01:00:00Z"));
        ApiClient smo = signedInAs(SMO_A);

        JsonNode reasons = refusalReasons(submitAccepting(smo, sub));

        assertThat(codes(reasons)).containsExactly("files-future-dated");
        assertThat(named(reason(reasons, "files-future-dated").get("files")))
                .containsExactly("dc.json (DEPENDENCY_CHECK)");
    }

    @Test
    void theReasonsNeverCarryReportContent() {
        Draft draft = draft();
        String sub = draft.submissionId();
        modeSet(draft.smo(), sub, SECURE_CONFIGURATION, mode(MANUAL, "Justified " + SENTINEL));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, SENTINEL, SENTINEL, SENTINEL, "LOW"));

        var response = submitAccepting(draft.smo(), sub);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).doesNotContain(SENTINEL);
        assertThat(entries("SUBMISSION_SUBMITTED", draft.systemId()).toString()).doesNotContain(SENTINEL);
    }

    /** The ids of the VA Submission's Findings that have no proposed status yet. */
    private static List<String> unproposed(Draft draft) {
        List<String> ids = new ArrayList<>();
        for (JsonNode finding : findings(draft.smo(), draft.submissionId()).get("items")) {
            if (finding.get("currentProposal").isNull()) {
                ids.add(finding.get("id").asString());
            }
        }
        return ids;
    }

    /** SYS-F2's VA Submission, started by its SMO/Tech Lead SMO_B; the Register is the one {@link #readyDraft} made. */
    private Draft otherDraft() {
        ApiClient smo = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        return new Draft(smo, system, started(smo, system).get("id").asString());
    }
}
