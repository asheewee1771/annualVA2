package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 23 (ticket 22): {@code POST /api/submissions/{id}/findings/manual} lets the SMO/Tech Lead type in a Finding of a Scan
 * Report marked for Findings entered by hand: its title, identifier, package or rule and severity. It is a Finding like any
 * other (it needs a Finding Status before the VA Submission can be submitted) and is always shown as Manually Entered
 * ({@code source} MANUAL), for the Cyber Manager to check more closely.
 */
@ExtendWith(OutputCaptureExtension.class)
class ManualFindingTest extends ScanReportSeam1 {

    private static final String LOG4SHELL = manualFinding(DEPENDENCY_CHECK, "Remote code execution in Log4j",
            "CVE-2021-44228", "log4j-core 2.14.1", "CRITICAL");

    @Test
    void aFindingEnteredByHandIsListedAsManuallyEnteredWithNoPlaceAndNoStatusYet() {
        Draft draft = manualDraft();

        JsonNode kept = entered(draft.smo(), draft.submissionId(), LOG4SHELL);

        assertThat(kept.get("id").asString()).isNotBlank();
        assertThat(kept.get("source").asString()).isEqualTo("MANUAL");
        assertThat(kept.get("reportType").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(kept.get("title").asString()).isEqualTo("Remote code execution in Log4j");
        assertThat(kept.get("identifier").asString()).isEqualTo("CVE-2021-44228");
        assertThat(kept.get("packageOrRule").asString()).isEqualTo("log4j-core 2.14.1");
        assertThat(kept.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(kept.get("occurrenceCount").asInt()).as("no file reported it").isZero();
        assertThat(kept.get("sourceFiles")).isEmpty();
        assertThat(kept.get("didNotComplete").asBoolean()).isFalse();
        assertThat(kept.get("confidence").isNull()).isTrue();
        assertThat(kept.get("currentProposal").isNull()).isTrue();
        assertThat(kept.get("reviewState").isNull()).isTrue();
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0)).as("the list shows what was kept").isEqualTo(kept);
        JsonNode attention = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?needsAttention=true"));
        assertThat(attention.get("total").asInt()).as("it needs a status").isEqualTo(1);
        JsonNode readiness = ApiClient.json(draft.smo().get(SUBMISSIONS + "/" + draft.submissionId() + "/readiness"));
        assertThat(readiness.get("total").asInt()).isEqualTo(1);
        assertThat(readiness.get("unproposed").asInt()).isEqualTo(1);
    }

    @Test
    void aParsedFindingIsMarkedParsedWithNoTitle() {
        Draft draft = draftWithFindings();

        JsonNode parsed = finding(draft.smo(), draft.submissionId(), "CVE-2021-44228");

        assertThat(parsed.get("source").asString()).isEqualTo("PARSED");
        assertThat(parsed.get("title").isNull()).isTrue();
    }

    @Test
    void aFindingEnteredByHandTakesAFindingStatusLikeAnyOtherAndTheCyberManagerSeesItMarked() {
        Draft draft = manualDraft();
        String id = entered(draft.smo(), draft.submissionId(), LOG4SHELL).get("id").asString();

        proposed(draft.smo(), id, remediated("Upgraded to 2.17.1."));

        for (ApiClient reader : List.of(draft.smo(), signedInAs(CM), vaPoc())) {
            JsonNode finding = finding(reader, draft.submissionId(), "CVE-2021-44228");
            assertThat(finding.get("source").asString()).isEqualTo("MANUAL");
            assertThat(finding.get("currentProposal").get("status").asString()).isEqualTo("REMEDIATED");
            assertThat(finding.get("reviewState").asString()).isEqualTo("PENDING");
        }
    }

    @Test
    void secureConfigurationAndBurpSuiteFindingsAreEnteredByHandToo() {
        Draft draft = draft();
        modeSet(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, mode(MANUAL, "The benchmark tool exports PDF."));
        modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(MANUAL, "The scanner exports HTML only."));

        entered(draft.smo(), draft.submissionId(), manualFinding(SECURE_CONFIGURATION, "Password policy too weak",
                "CIS 1.1.4", "Minimum password length", "MEDIUM"));
        entered(draft.smo(), draft.submissionId(), manualFinding(BURP_SUITE, "Reflected cross-site scripting",
                "XSS-REFLECTED", "/search?q=", "HIGH"));

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(2);
        assertThat(byIdentifier(page, "CIS 1.1.4").get("reportType").asString()).isEqualTo(SECURE_CONFIGURATION);
        assertThat(byIdentifier(page, "XSS-REFLECTED").get("reportType").asString()).isEqualTo(BURP_SUITE);
    }

    @Test
    void onlyAScanReportMarkedForFindingsEnteredByHandTakesOne() {
        Draft draft = draft();
        String id = draft.submissionId();
        modeSet(draft.smo(), id, BURP_SUITE, mode(NOT_APPLICABLE, "No web front end."));

        assertConflict(enter(draft.smo(), id, LOG4SHELL), "wrong-report-mode");
        assertConflict(enter(draft.smo(), id, manualFinding(BURP_SUITE, "XSS", "XSS-1", "/search", "HIGH")),
                "wrong-report-mode");

        assertThat(findings(draft.smo(), id).get("total").asInt()).isZero();
        JsonNode entries = entries("FINDING_ENTERED", draft.systemId());
        assertThat(entries).hasSize(2);
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
            assertThat(entry.get("detail").get("reason").asString()).isEqualTo("wrong-report-mode");
        }
    }

    @Test
    void theSameFindingIsEnteredOnceAndAnotherPackageIsAnotherFinding() {
        Draft draft = manualDraft();
        entered(draft.smo(), draft.submissionId(), LOG4SHELL);

        assertConflict(enter(draft.smo(), draft.submissionId(), manualFinding(DEPENDENCY_CHECK, "Another title",
                "CVE-2021-44228", "log4j-core 2.14.1", "HIGH")), "duplicate-finding");
        entered(draft.smo(), draft.submissionId(), manualFinding(DEPENDENCY_CHECK, "Same CVE, other package",
                "CVE-2021-44228", "log4j-core 2.15.0", "CRITICAL"));

        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isEqualTo(2);
        long refused = java.util.stream.StreamSupport
                .stream(entries("FINDING_ENTERED", draft.systemId()).spliterator(), false)
                .filter(entry -> entry.get("outcome").asString().equals("REFUSED"))
                .peek(entry -> assertThat(entry.get("detail").get("reason").asString()).isEqualTo("duplicate-finding"))
                .count();
        assertThat(refused).isEqualTo(1);
    }

    @Test
    void everyFieldIsCheckedInOrderAndTheFirstWrongOneIsNamed() {
        Draft draft = manualDraft();
        String id = draft.submissionId();

        assertInvalidField(enter(draft.smo(), id, manualFinding(null, "t", "i", "p", "LOW")), "reportType");
        assertInvalidField(enter(draft.smo(), id, manualFinding("NESSUS", "t", "i", "p", "LOW")), "reportType");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, null, "i", "p", "LOW")), "title");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, " ​\t", "i", "p", "LOW")),
                "title");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t".repeat(201), "i", "p", "LOW")),
                "title");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", null, "p", "LOW")), "identifier");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i".repeat(201), "p", "LOW")),
                "identifier");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i", null, "LOW")),
                "packageOrRule");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i", "p".repeat(501), "LOW")),
                "packageOrRule");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i", "p", null)), "severity");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i", "p", "SEVERE")), "severity");
        assertInvalidField(enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i", "p", "high")), "severity");
        assertInvalidField(enter(draft.smo(), id, "{}"), "reportType");
        assertThat(enter(draft.smo(), id, "{\"reportType\":").status()).isEqualTo(400);

        assertThat(findings(draft.smo(), id).get("total").asInt()).as("nothing was kept").isZero();
        assertThat(entries("FINDING_ENTERED", draft.systemId())).as("a 400 is not audited").isEmpty();
    }

    @Test
    void eachTextIsCleanedToOneLineAndMayBeExactlyItsLimit() {
        Draft draft = manualDraft();

        JsonNode cleaned = entered(draft.smo(), draft.submissionId(), manualFinding(DEPENDENCY_CHECK,
                "  Remote\ncode‮ execution\u0000 ", "\tCVE-2021-44228​", "log4j-core\n2.14.1", "CRITICAL"));
        JsonNode longest = entered(draft.smo(), draft.submissionId(), manualFinding(DEPENDENCY_CHECK, "t".repeat(200),
                "i".repeat(200), "p".repeat(500), "INFORMATIONAL"));

        assertThat(cleaned.get("title").asString()).isEqualTo("Remote code execution");
        assertThat(cleaned.get("identifier").asString()).isEqualTo("CVE-2021-44228");
        assertThat(cleaned.get("packageOrRule").asString()).isEqualTo("log4j-core 2.14.1");
        assertThat(longest.get("title").asString()).hasSize(200);
        assertThat(longest.get("identifier").asString()).hasSize(200);
        assertThat(longest.get("packageOrRule").asString()).hasSize(500);
        assertThat(longest.get("severity").asString()).isEqualTo("INFORMATIONAL");
    }

    @Test
    void onlyTheSmoOfTheSystemEntersAFindingAndOnlyInDraftOrReturnedForRework() {
        Draft draft = manualDraft();
        String id = draft.submissionId();

        for (ApiClient other : List.of(signedInAs(CM), vaPoc())) {
            assertThat(enter(other, id, LOG4SHELL).status()).isEqualTo(403);
        }
        var stranger = enter(signedInAs(SMO_B), id, LOG4SHELL);
        var missing = enter(draft.smo(), "00000000-0000-4000-8000-000000000000", LOG4SHELL);
        assertNotFound(stranger);
        assertNotFound(missing);
        assertThat(ApiClient.json(stranger).get("detail")).isEqualTo(ApiClient.json(missing).get("detail"));
        assertThat(anotherBrowser().withCsrf().postJson(manualOf(id), LOG4SHELL).status()).isEqualTo(401);

        for (String state : List.of("IN_REVIEW", "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED",
                "WITHDRAWN", "NOT_ENDORSED")) {
            arrange.submissionState(id, state);
            assertConflict(enter(draft.smo(), id, LOG4SHELL), "invalid-transition");
        }
        assertThat(findings(draft.smo(), id).get("total").asInt()).isZero();
        arrange.submissionState(id, "RETURNED_FOR_REWORK");
        entered(draft.smo(), id, LOG4SHELL);

        JsonNode entries = entries("FINDING_ENTERED", draft.systemId());
        assertThat(entries).as("seven refusals for the state and the entry; not the 403s or 404s").hasSize(8);
        for (JsonNode entry : entries) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
            }
        }
    }

    @Test
    void aScanReportWithAFindingEnteredByHandKeepsItsModeButItsJustificationCanChange() {
        Draft draft = manualDraft();
        entered(draft.smo(), draft.submissionId(), LOG4SHELL);

        for (String other : List.of(UPLOAD, NOT_APPLICABLE)) {
            assertConflict(setMode(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK,
                    mode(other, other.equals(UPLOAD) ? null : "Not a Java system.")), "report-not-empty");
        }
        JsonNode changed = modeSet(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK,
                mode(MANUAL, "The tool exports PDF and HTML only."));

        assertThat(changed.get("justification").asString()).isEqualTo("The tool exports PDF and HTML only.");
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isEqualTo(1);
    }

    @Test
    void aScanReportEnteredByHandHasNoParsedFindingsAndAParsedOneTakesNoneByHand() {
        Draft draft = draftWithFindings();

        assertConflict(setMode(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, mode(MANUAL, "Exports PDF.")),
                "report-not-empty");
        assertConflict(enter(draft.smo(), draft.submissionId(), LOG4SHELL), "wrong-report-mode");
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isEqualTo(7);
    }

    @Test
    void anEntryIsAuditedAboutTheFindingWithTheScanReportAndNothingThePersonTyped(CapturedOutput output) {
        Draft draft = manualDraft();
        String id = draft.submissionId();

        JsonNode kept = entered(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "Title " + SENTINEL,
                "ID-" + SENTINEL, "Package " + SENTINEL, "HIGH"));
        List<String> bodies = List.of(
                enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "Title " + SENTINEL, "ID-" + SENTINEL,
                        "Package " + SENTINEL, "LOW")).body(),
                enter(draft.smo(), id, manualFinding(SENTINEL, "t", "i", "p", "LOW")).body(),
                enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, SENTINEL + "t".repeat(201), "i", "p", "LOW")).body(),
                enter(draft.smo(), id, manualFinding(DEPENDENCY_CHECK, "t", "i", "p", SENTINEL)).body(),
                enter(draft.smo(), id, manualFinding(BURP_SUITE, SENTINEL, SENTINEL, SENTINEL, "LOW")).body(),
                enter(signedInAs(SMO_B), id, manualFinding(DEPENDENCY_CHECK, SENTINEL, SENTINEL, SENTINEL, "LOW")).body());

        JsonNode entries = entries("FINDING_ENTERED", draft.systemId());
        JsonNode success = java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .filter(entry -> entry.get("outcome").asString().equals("SUCCESS")).findFirst().orElseThrow();
        assertThat(success.get("findingId").asString()).isEqualTo(kept.get("id").asString());
        assertThat(success.get("submissionId").asString()).isEqualTo(id);
        assertThat(success.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(success.get("detail").propertyNames()).containsExactly("report_type");
        assertThat(success.get("detail").get("report_type").asString()).isEqualTo(DEPENDENCY_CHECK);
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        String audit = otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body();
        assertThat(audit).doesNotContain(SENTINEL).contains("FINDING_ENTERED");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL).contains("finding-entered");
        assertThat(String.join("\n", bodies)).as("error bodies").doesNotContain(SENTINEL);
        assertThat(kept.toString()).as("the Finding is data, so it is returned").contains(SENTINEL);
    }

    @Test
    void aFindingEnteredByHandIsNeverMatchedByAParsedFile() {
        Draft draft = draft();
        modeSet(draft.smo(), draft.submissionId(), BURP_SUITE, mode(MANUAL, "Exports HTML only."));
        entered(draft.smo(), draft.submissionId(), manualFinding(BURP_SUITE, "Log4Shell seen by Burp", "CVE-2021-44228",
                "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1", "CRITICAL"));

        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).as("seven parsed and one by hand").isEqualTo(8);
    }
}
