package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 23 (ticket 22): {@code PUT /api/submissions/{id}/reports/{type}/mode} says how a Scan Report is provided: its tool's
 * report is uploaded (UPLOAD, the default), it does not apply to the System (NOT_APPLICABLE, Dependency-Check or Burp Suite
 * only, with a justification the Cyber Manager reviews like a Finding) or its Findings are entered by hand (MANUAL, with a
 * justification). Secure Configuration is always required. {@code GET /api/submissions/{id}/reports} reads the three.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScanReportModeTest extends ScanReportSeam1 {

    @Test
    void everyScanReportOfANewVaSubmissionIsUploaded() {
        Draft draft = draft();

        JsonNode reports = reports(draft.smo(), draft.submissionId());

        assertThat(reports).hasSize(3);
        List<String> types = new ArrayList<>();
        for (JsonNode report : reports) {
            types.add(report.get("reportType").asString());
            assertThat(report.get("mode").asString()).isEqualTo(UPLOAD);
            assertThat(report.get("justification").isNull()).isTrue();
            assertThat(report.get("reviewState").isNull()).as("nothing to review").isTrue();
        }
        assertThat(types).containsExactly(DEPENDENCY_CHECK, SECURE_CONFIGURATION, BURP_SUITE);
    }

    @Test
    void dependencyCheckAndBurpSuiteAreMarkedNotApplicableWithAJustificationThatAwaitsReview() {
        Draft draft = draft();

        for (String type : List.of(DEPENDENCY_CHECK, BURP_SUITE)) {
            JsonNode kept = modeSet(draft.smo(), draft.submissionId(), type, mode(NOT_APPLICABLE, "No web front end."));

            assertThat(kept.get("reportType").asString()).isEqualTo(type);
            assertThat(kept.get("mode").asString()).isEqualTo(NOT_APPLICABLE);
            assertThat(kept.get("justification").asString()).isEqualTo("No web front end.");
            assertThat(kept.get("reviewState").asString()).as("the Cyber Manager reviews it").isEqualTo("PENDING");
            assertThat(report(draft.smo(), draft.submissionId(), type)).isEqualTo(kept);
        }
        assertThat(report(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION).get("mode").asString())
                .isEqualTo(UPLOAD);
    }

    @Test
    void secureConfigurationIsAlwaysRequiredAndCannotBeNotApplicable() {
        Draft draft = draft();

        var refused = setMode(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION,
                mode(NOT_APPLICABLE, "We have no servers."));

        assertInvalidField(refused, "mode");
        assertThat(ApiClient.json(refused).get("detail").asString()).contains("Secure Configuration");
        assertThat(report(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION).get("mode").asString())
                .isEqualTo(UPLOAD);
    }

    @Test
    void anyScanReportSecureConfigurationIncludedIsEnteredByHandWithAJustificationAndNoReview() {
        Draft draft = draft();

        for (String type : List.of(DEPENDENCY_CHECK, SECURE_CONFIGURATION, BURP_SUITE)) {
            JsonNode kept = modeSet(draft.smo(), draft.submissionId(), type, mode(MANUAL, "The tool exports PDF only."));

            assertThat(kept.get("mode").asString()).isEqualTo(MANUAL);
            assertThat(kept.get("justification").asString()).isEqualTo("The tool exports PDF only.");
            assertThat(kept.get("reviewState").isNull()).as("its Findings are reviewed, not the mode").isTrue();
        }
    }

    @Test
    void backToUploadForgetsTheJustificationAndTheReview() {
        Draft draft = draft();
        modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(NOT_APPLICABLE, "No web front end."));

        JsonNode kept = modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(UPLOAD, null));

        assertThat(kept.get("mode").asString()).isEqualTo(UPLOAD);
        assertThat(kept.get("justification").isNull()).isTrue();
        assertThat(kept.get("reviewState").isNull()).isTrue();
        assertThat(report(draft.smo(), draft.submissionId(), BURP_SUITE)).isEqualTo(kept);
    }

    @Test
    void aNewJustificationPutsAReviewedOneBackToPendingAndTheSameOneKeepsTheReview() {
        Draft draft = draft();
        String justification = "No web front end.";
        modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(NOT_APPLICABLE, justification));

        for (String state : List.of("CONFIRMED", "DISPUTED")) {
            arrange.reportReview(draft.submissionId(), BURP_SUITE, state);
            JsonNode same = modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(NOT_APPLICABLE, justification));
            assertThat(same.get("reviewState").asString()).as("nothing new to review").isEqualTo(state);

            justification = "No web front end, after " + state + ".";
            JsonNode changed = modeSet(draft.smo(), draft.submissionId(), BURP_SUITE,
                    mode(NOT_APPLICABLE, justification));
            assertThat(changed.get("reviewState").asString()).as("after " + state).isEqualTo("PENDING");
        }
        arrange.reportReview(draft.submissionId(), BURP_SUITE, "CONFIRMED");
        modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(MANUAL, "Exports PDF only."));
        JsonNode again = modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(NOT_APPLICABLE, "No web front end."));
        assertThat(again.get("reviewState").asString()).as("marked again after another mode").isEqualTo("PENDING");
    }

    @Test
    void theModeAndTheJustificationAreCheckedAndTheFirstWrongFieldIsNamed() {
        Draft draft = draft();
        String id = draft.submissionId();

        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, "{}"), "mode");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode("SKIPPED", "x")), "mode");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode("not_applicable", "x")), "mode");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, null)), "justification");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode(MANUAL, null)), "justification");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode(MANUAL, " \u0007​\t ")), "justification");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, "x".repeat(4001))),
                "justification");
        assertInvalidField(setMode(draft.smo(), id, BURP_SUITE, mode(UPLOAD, "Nothing to justify.")), "justification");
        var malformed = setMode(draft.smo(), id, BURP_SUITE, "{\"mode\":");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(draft.smo().putJson(reportsOf(id) + "/NESSUS/mode", mode(MANUAL, "x")).status())
                .as("not a Scan Report").isEqualTo(400);

        assertThat(report(draft.smo(), id, BURP_SUITE).get("mode").asString()).as("nothing was kept").isEqualTo(UPLOAD);
    }

    @Test
    void theJustificationIsCleanedLikeEveryTextAPersonTypesAndMayBeExactlyTheLimit() {
        Draft draft = draft();

        JsonNode cleaned = modeSet(draft.smo(), draft.submissionId(), BURP_SUITE,
                mode(NOT_APPLICABLE, "  No web‮ front end.\nOnly a batch job.\u0000  "));
        JsonNode longest = modeSet(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK,
                mode(NOT_APPLICABLE, "y".repeat(4000)));

        assertThat(cleaned.get("justification").asString()).isEqualTo("No web front end.\nOnly a batch job.");
        assertThat(longest.get("justification").asString()).hasSize(4000);
    }

    @Test
    void theModeChangesOnlyWhileTheScanReportHasNoReportFileAndNoFinding() {
        Draft draft = draft();
        String id = draft.submissionId();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        var refused = setMode(draft.smo(), id, DEPENDENCY_CHECK, mode(NOT_APPLICABLE, "Not a Java system."));
        assertConflict(refused, "report-not-empty");
        assertConflict(setMode(draft.smo(), id, DEPENDENCY_CHECK, mode(MANUAL, "Exports PDF only.")), "report-not-empty");
        assertThat(report(draft.smo(), id, DEPENDENCY_CHECK).get("mode").asString()).isEqualTo(UPLOAD);
        assertThat(modeSet(draft.smo(), id, DEPENDENCY_CHECK, mode(UPLOAD, null)).get("mode").asString())
                .as("the mode it already has").isEqualTo(UPLOAD);

        String scanning = uploaded(draft.smo(), id, BURP_SUITE, "burp.xml", Samples.burpSample()).get("id").asString();
        assertConflict(setMode(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, "No web front end.")),
                "report-not-empty");
        arrange.rejectFile(scanning);
        uploadedEvidence(draft.smo(), id, BURP_SUITE, "memo.pdf", Samples.pdf());
        assertThat(modeSet(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, "No web front end.")).get("mode").asString())
                .as("a rejected file and supporting evidence are no report").isEqualTo(NOT_APPLICABLE);

        JsonNode entries = entries("REPORT_MODE_SET", draft.systemId());
        long refusals = java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .filter(entry -> entry.get("outcome").asString().equals("REFUSED")).count();
        assertThat(refusals).isEqualTo(3);
        for (JsonNode entry : entries) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").get("reason").asString()).isEqualTo("report-not-empty");
            }
        }
    }

    @Test
    void aScanReportNotUploadedTakesNoReportFileButStillTakesSupportingEvidence() {
        Draft draft = draft();
        String id = draft.submissionId();
        modeSet(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, "No web front end."));
        modeSet(draft.smo(), id, SECURE_CONFIGURATION, mode(MANUAL, "Exports PDF only."));

        int quarantined = fileStore.count(FileStore.Zone.QUARANTINE);
        for (String type : List.of(BURP_SUITE, SECURE_CONFIGURATION)) {
            var refused = upload(draft.smo(), id, type, MACHINE_READABLE, "report.xml", Samples.burpSample());
            assertConflict(refused, "wrong-report-mode");
        }
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("nothing refused stays in quarantine")
                .isEqualTo(quarantined);
        uploadedEvidence(draft.smo(), id, SECURE_CONFIGURATION, "benchmark.pdf", Samples.pdf());

        JsonNode files = ApiClient.json(draft.smo().get(filesOf(id)));
        assertThat(files).as("only the evidence was kept").hasSize(1);
        JsonNode refusals = entries("FILE_UPLOADED", draft.systemId());
        long wrongMode = java.util.stream.StreamSupport.stream(refusals.spliterator(), false)
                .filter(entry -> entry.get("outcome").asString().equals("REFUSED")).count();
        assertThat(wrongMode).isEqualTo(2);
        for (JsonNode entry : refusals) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").get("reason").asString()).isEqualTo("wrong-report-mode");
            }
        }
    }

    @Test
    void onlyTheSmoOfTheSystemChangesTheModeAndOnlyInDraftOrReturnedForRework() {
        Draft draft = draft();
        String id = draft.submissionId();
        String body = mode(NOT_APPLICABLE, "No web front end.");

        for (ApiClient other : List.of(signedInAs(CM), vaPoc())) {
            assertThat(setMode(other, id, BURP_SUITE, body).status()).isEqualTo(403);
        }
        var stranger = setMode(signedInAs(SMO_B), id, BURP_SUITE, body);
        var missing = setMode(draft.smo(), "00000000-0000-4000-8000-000000000000", BURP_SUITE, body);
        assertNotFound(stranger);
        assertNotFound(missing);
        assertThat(ApiClient.json(stranger).get("detail")).isEqualTo(ApiClient.json(missing).get("detail"));
        assertThat(anotherBrowser().withCsrf().putJson(modeOf(id, BURP_SUITE), body).status()).isEqualTo(401);

        for (String state : List.of("IN_REVIEW", "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED",
                "WITHDRAWN", "NOT_ENDORSED")) {
            arrange.submissionState(id, state);
            assertConflict(setMode(draft.smo(), id, BURP_SUITE, body), "invalid-transition");
        }
        assertThat(report(draft.smo(), id, BURP_SUITE).get("mode").asString()).isEqualTo(UPLOAD);
        arrange.submissionState(id, "RETURNED_FOR_REWORK");
        modeSet(draft.smo(), id, BURP_SUITE, body);

        JsonNode entries = entries("REPORT_MODE_SET", draft.systemId());
        assertThat(entries).as("seven refusals for the state and the change; not the 403s or 404s").hasSize(8);
        for (JsonNode entry : entries) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
            }
        }
    }

    @Test
    void anyoneWhoMaySeeTheSystemReadsTheScanReportsAndEveryReadIsAuditedWithTheCountOnly() {
        Draft draft = draft();
        modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(NOT_APPLICABLE, "No web front end " + SENTINEL));

        for (ApiClient reader : List.of(draft.smo(), signedInAs(CM), vaPoc())) {
            assertThat(report(reader, draft.submissionId(), BURP_SUITE).get("justification").asString())
                    .contains(SENTINEL);
        }
        assertNotFound(signedInAs(SMO_B).get(reportsOf(draft.submissionId())));
        assertNotFound(draft.smo().get(reportsOf("00000000-0000-4000-8000-000000000000")));
        assertThat(anotherBrowser().get(reportsOf(draft.submissionId())).status()).isEqualTo(401);

        JsonNode entries = entries("SCAN_REPORTS_VIEWED", draft.systemId());
        assertThat(entries).as("the owner too, as the justification is what a person wrote").hasSize(3);
        for (JsonNode entry : entries) {
            assertThat(entry.get("detail").propertyNames()).containsExactly("count");
            assertThat(entry.get("detail").get("count").asInt()).isEqualTo(3);
            assertThat(entry.toString()).doesNotContain(SENTINEL);
        }
    }

    @Test
    void aModeChangeIsAuditedWithTheScanReportAndTheModeAndNothingThePersonWrote(CapturedOutput output) {
        Draft draft = draft();
        String id = draft.submissionId();

        modeSet(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, "No web front end " + SENTINEL));
        modeSet(draft.smo(), id, SECURE_CONFIGURATION, mode(MANUAL, "Exports PDF " + SENTINEL));
        List<String> bodies = List.of(
                setMode(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, SENTINEL + "x".repeat(4001))).body(),
                setMode(draft.smo(), id, BURP_SUITE, mode(SENTINEL, "x")).body(),
                setMode(draft.smo(), id, SECURE_CONFIGURATION, mode(NOT_APPLICABLE, SENTINEL)).body(),
                setMode(draft.smo(), id, BURP_SUITE, mode(UPLOAD, SENTINEL)).body(),
                setMode(signedInAs(SMO_B), id, BURP_SUITE, mode(MANUAL, SENTINEL)).body());

        JsonNode entries = entries("REPORT_MODE_SET", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("detail").get("report_type").asString()).isEqualTo(SECURE_CONFIGURATION);
        assertThat(entries.get(0).get("detail").get("mode").asString()).isEqualTo(MANUAL);
        assertThat(entries.get(1).get("detail").get("report_type").asString()).isEqualTo(BURP_SUITE);
        assertThat(entries.get(1).get("detail").get("mode").asString()).isEqualTo(NOT_APPLICABLE);
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
            assertThat(entry.get("submissionId").asString()).isEqualTo(id);
            assertThat(entry.get("detail").propertyNames()).containsExactlyInAnyOrder("report_type", "mode");
        }
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        String audit = otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body();
        assertThat(audit).doesNotContain(SENTINEL).contains("REPORT_MODE_SET");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL).contains("report-mode-set");
        assertThat(String.join("\n", bodies)).as("error bodies").doesNotContain(SENTINEL);
    }
}
