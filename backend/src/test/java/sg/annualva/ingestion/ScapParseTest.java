package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 20b, 21, 25, 28 and 29: a CIS-CAT Pro or OpenSCAP results file (ARF or XCCDF) uploaded as the Secure Configuration
 * Scan Report is read into Findings for the checks that failed and the checks that did not complete, one Finding per rule
 * and one occurrence per host; passed, not applicable and not checked results are ignored; the scan date and target are read
 * for the SMO/Tech Lead to confirm.
 */
class ScapParseTest extends ScapSeam1 {

    @Test
    void aCisCatXccdfResultsFileIsReadIntoFindingsForFailedChecksAndChecksThatDidNotComplete() {
        Draft draft = draft();

        JsonNode file = settledScap(draft, "cis.xml", Samples.cisCatXccdfSample());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("XCCDF_RESULTS");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK",
                "PARSED");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(4);
        assertThat(findingSummary(page)).containsExactlyEntriesOf(cisCatFindings());
    }

    @Test
    void anOpenScapArfWithTwoHostsIsOneFindingPerRuleWithAnOccurrenceForEachHost() {
        Draft draft = draft();

        JsonNode file = settledScap(draft, "arf.xml", Samples.openScapArfSample());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("ARF");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(6);
        assertThat(findingSummary(page)).containsExactlyEntriesOf(openScapFindings());
    }

    @Test
    void eachFindingCarriesItsReportTypeRuleSeverityAndSourceFileScopedToTheSystem() {
        Draft draft = draft();
        String id = settledScap(draft, "arf.xml", Samples.openScapArfSample()).get("id").asString();

        JsonNode page = findings(draft.smo(), draft.submissionId());

        JsonNode sshd = byIdentifier(page, SSG_RULE + "sshd_disable_root_login");
        assertThat(sshd.get("reportType").asString()).isEqualTo(SECURE_CONFIGURATION);
        assertThat(sshd.get("identityKey").asString()).isEqualTo("scap|" + SSG_RULE + "sshd_disable_root_login");
        assertThat(sshd.get("packageOrRule").asString()).as("the rule").isEqualTo(SSG_RULE + "sshd_disable_root_login");
        assertThat(sshd.get("severity").asString()).isEqualTo("HIGH");
        assertThat(sshd.get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(sshd.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(sshd.get("sourceFiles")).hasSize(1);
        assertThat(sshd.get("sourceFiles").get(0).get("id").asString()).isEqualTo(id);
        assertThat(sshd.get("sourceFiles").get(0).get("name").asString()).isEqualTo("arf.xml");
    }

    @Test
    void passedNotApplicableAndNotCheckedResultsAreIgnoredAndSoIsAnyResultThatIsNeitherAFailNorAnIncompleteCheck() {
        Draft draft = draft();
        String[] results = {
            ruleResult("r.pass", "high", "pass"), ruleResult("r.notapplicable", "high", "notapplicable"),
            ruleResult("r.notchecked", "high", "notchecked"), ruleResult("r.notselected", "high", "notselected"),
            ruleResult("r.informational", "high", "informational"), ruleResult("r.fixed", "high", "fixed"),
            ruleResult("r.unheard-of", "high", "unheard-of"), ruleResult("r.empty", "high", ""),
            ruleResult("r.fail", "high", "fail"), ruleResult("r.error", "high", "error"),
            ruleResult("r.unknown", "high", "unknown")
        };

        JsonNode file = settledScap(draft, "results.xml", xccdf(testResult("host-a.invalid", null, results)));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scap|r.fail", "HIGH/1", "scap|r.error", "HIGH/1/INCOMPLETE", "scap|r.unknown", "HIGH/1/INCOMPLETE"));
    }

    @Test
    void theSeverityIsTheOneTheResultCarriesAndAnUnratedCheckIsMedium() {
        Draft draft = draft();
        Map<String, String> ratings = new LinkedHashMap<>();
        ratings.put("high", "HIGH");
        ratings.put("medium", "MEDIUM");
        ratings.put("low", "LOW");
        ratings.put("info", "INFORMATIONAL");
        ratings.put("HIGH", "HIGH");
        ratings.put("unknown", "MEDIUM");
        ratings.put("", "MEDIUM");
        ratings.put("unheard-of", "MEDIUM");
        ratings.put(null, "MEDIUM");
        List<String> results = new java.util.ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        int n = 0;
        for (var rating : ratings.entrySet()) {
            String rule = "r." + n++;
            results.add(ruleResult(rule, rating.getKey(), "fail"));
            expected.put("scap|" + rule, rating.getValue() + "/1");
        }

        settledScap(draft, "severity.xml", xccdf(testResult("host-a.invalid", null, results.toArray(String[]::new))));

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void aCheckThatFailedOnOneHostAndGaveAnErrorOnAnotherIsAFailedCheckAndOneThatErroredEverywhereDidNotComplete() {
        Draft draft = draft();
        byte[] report = xccdf(
                testResult("host-a.invalid", null, ruleResult("r.mixed", "low", "fail"),
                        ruleResult("r.everywhere", "low", "error")),
                testResult("host-b.invalid", null, ruleResult("r.mixed", "low", "error"),
                        ruleResult("r.everywhere", "low", "unknown")));

        settledScap(draft, "two-hosts.xml", report);

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scap|r.mixed", "LOW/2", "scap|r.everywhere", "LOW/2/INCOMPLETE"));
    }

    @Test
    void aRuleResultedTwiceForOneHostIsOnePlaceThatDidNotCompleteOnlyIfEveryResultDidNot() {
        Draft draft = draft();
        byte[] report = xccdf(testResult("host-a.invalid", null,
                ruleResult("r.fail-then-error", "high", "fail"), ruleResult("r.fail-then-error", "low", "error"),
                ruleResult("r.error-then-fail", "low", "error"), ruleResult("r.error-then-fail", "medium", "fail"),
                ruleResult("r.errors", "low", "error"), ruleResult("r.errors", "medium", "unknown")));

        settledScap(draft, "twice.xml", report);

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scap|r.fail-then-error", "HIGH/1", "scap|r.error-then-fail", "MEDIUM/1",
                "scap|r.errors", "MEDIUM/1/INCOMPLETE"));
    }

    @Test
    void aHostRunTwiceInOneFileIsOnePlacePerRuleAndAnErrorInOneRunDoesNotHideAFailureInTheOther() {
        Draft draft = draft();
        byte[] report = xccdf(
                testResult("host-a.invalid", null, ruleResult("r.fail-then-error", "high", "fail"),
                        ruleResult("r.error-twice", "low", "error"), ruleResult("r.only-first", "low", "fail")),
                testResult("host-a.invalid", null, ruleResult("r.fail-then-error", "low", "error"),
                        ruleResult("r.error-twice", "medium", "unknown"), ruleResult("r.only-second", "low", "error")));

        settledScap(draft, "twice.xml", report);

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scap|r.fail-then-error", "HIGH/1", "scap|r.error-twice", "MEDIUM/1/INCOMPLETE",
                "scap|r.only-first", "LOW/1", "scap|r.only-second", "LOW/1/INCOMPLETE"));
    }

    @Test
    void theScanDateIsTheLatestEndTimeAndTheTargetIsTheHostsTheFileNames() {
        Draft draft = draft();
        String xccdf = settledScap(draft, "cis.xml", Samples.cisCatXccdfSample()).get("id").asString();
        String arf = settledScap(draft, "arf.xml", Samples.openScapArfSample()).get("id").asString();

        JsonNode single = metadata(draft.smo(), draft.submissionId(), xccdf);
        JsonNode both = metadata(draft.smo(), draft.submissionId(), arf);

        assertThat(single.get("target").asString()).isEqualTo("throwaway-host-01");
        assertThat(single.get("scanDate").asString()).as("10:03:44 at +08:00, in UTC").startsWith("2027-07-20T02:03:44");
        assertThat(both.get("target").asString()).isEqualTo("throwaway-host-02, throwaway-host-03");
        assertThat(both.get("scanDate").asString()).as("the later of the two, 11:20:40 at +08:00")
                .startsWith("2027-07-20T03:20:40");
    }

    @Test
    void aHostWithNoNameIsKnownByItsAddressAndThenItsResultByItsId() {
        Draft draft = draft();
        String byAddress = "<TestResult id=\"xccdf_by_address\"><target-address>192.0.2.77</target-address>"
                + ruleResult("r.a", null, "fail") + "</TestResult>";
        String byId = "<TestResult id=\"xccdf_by_id\">" + ruleResult("r.a", null, "fail") + "</TestResult>";

        String first = settledScap(draft, "address.xml", xccdf(byAddress)).get("id").asString();
        String second = settledScap(draft, "id.xml", xccdf(byId)).get("id").asString();

        assertThat(metadata(draft.smo(), draft.submissionId(), first).get("target").asString()).isEqualTo("192.0.2.77");
        assertThat(metadata(draft.smo(), draft.submissionId(), second).get("target").isNull())
                .as("the id of the result is a place, not a target to offer").isTrue();
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(Map.entry("scap|r.a",
                "MEDIUM/2"));
    }

    @Test
    void severalFilesOfOneScanReportMakeOneFindingPerRuleWithAnOccurrenceForEachFileAndTheWorstSeverity() {
        Draft draft = draft();
        settledScap(draft, "first.xml", xccdf(testResult("host-a.invalid", null, ruleResult("r.x", "low", "fail"),
                ruleResult("r.y", "medium", "fail"))));
        settledScap(draft, "second.xml", arf(testResult("host-b.invalid", null, ruleResult("r.x", "high", "error"),
                ruleResult("r.z", "low", "error"))));

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(findingSummary(page)).containsExactlyInAnyOrderEntriesOf(Map.of("scap|r.x", "HIGH/2",
                "scap|r.y", "MEDIUM/1", "scap|r.z", "LOW/1/INCOMPLETE"));
        assertThat(sourceNames(byIdentifier(page, "r.x"))).containsExactly("first.xml", "second.xml");
        assertThat(sourceNames(byIdentifier(page, "r.y"))).containsExactly("first.xml");
        assertThat(sourceNames(byIdentifier(page, "r.z"))).containsExactly("second.xml");
    }

    @Test
    void theXccdf11NamespaceAndABareTestResultRootAreAcceptedToo() {
        Draft draft = draft();
        byte[] old = xccdfIn(XCCDF_11, testResult("host-a.invalid", null, ruleResult("r.old", "high", "fail")));
        byte[] bare = utf8("<TestResult xmlns=\"" + XCCDF_12 + "\" id=\"xccdf_bare\" end-time=\"2027-07-20T10:00:00Z\">"
                + "<target>host-b.invalid</target>" + ruleResult("r.bare", "low", "fail") + "</TestResult>");

        JsonNode first = settledScap(draft, "xccdf11.xml", old);
        JsonNode second = settledScap(draft, "bare.xml", bare);

        assertThat(first.get("status").asString()).isEqualTo("PARSED");
        assertThat(second.get("status").asString()).isEqualTo("PARSED");
        assertThat(second.get("detectedFormat").asString()).isEqualTo("XCCDF_RESULTS");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scap|r.old", "HIGH/1", "scap|r.bare", "LOW/1"));
    }

    @Test
    void aResultsFileWhereEveryCheckPassedIsAcceptedWithNoFindings() {
        Draft draft = draft();

        JsonNode file = settledScap(draft, "clean.xml", xccdf(testResult("host-a.invalid", "2027-07-20T10:00:00Z",
                ruleResult("r.1", "high", "pass"), ruleResult("r.2", "low", "notapplicable"))));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), file.get("id").asString());
        assertThat(metadata.get("target").asString()).isEqualTo("host-a.invalid");
        assertThat(metadata.get("scanDate").asString()).startsWith("2027-07-20T10:00:00");
    }

    @Test
    void aVaSubmissionListsTheFindingsOfEveryScanReportTogetherEachWithItsOwnReportType() {
        Draft draft = draft();
        settled(draft, DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckSampleXml());
        settledScap(draft, "cis.xml", Samples.cisCatXccdfSample());

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(page.get("total").asInt()).isEqualTo(11);
        int dependencyCheck = 0;
        int secureConfiguration = 0;
        for (JsonNode finding : page.get("items")) {
            if (finding.get("reportType").asString().equals(DEPENDENCY_CHECK)) {
                dependencyCheck++;
                assertThat(finding.get("didNotComplete").asBoolean()).as("a vulnerability is never a check that did not "
                        + "complete").isFalse();
            } else {
                secureConfiguration++;
                assertThat(finding.get("identityKey").asString()).startsWith("scap|");
            }
        }
        assertThat(dependencyCheck).isEqualTo(7);
        assertThat(secureConfiguration).isEqualTo(4);
    }

    @Test
    void aFileIsNeverParsedWithoutItsFindingsOrTheOtherWayRound() {
        Draft draft = draft();
        String id = uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, "arf.xml",
                Samples.openScapArfSample()).get("id").asString();
        pass();
        // The last write of the settlement fails: the file row, its stage rows and its Findings were all written before it.
        arrange.findingFault("on");
        try {
            pass();

            JsonNode file = status(draft.smo(), draft.submissionId(), id);
            assertThat(file.get("status").asString()).as("the whole outcome was rolled back").isEqualTo("SCAN_SUBMITTED");
            assertThat(stages(file)).as("no stage of the failed attempt was kept")
                    .containsExactly("QUARANTINED", "SCAN_SUBMITTED");
            assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).as("no Finding was kept")
                    .isZero();
            assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.CLEAN)).as("nor a clean copy").isZero();
            assertThat(draft.smo().get(fileAt(draft.submissionId(), id) + "/metadata").status())
                    .as("nor what the file said about the scan").isEqualTo(404);
        } finally {
            arrange.findingFault("off");
        }

        pass();

        JsonNode file = status(draft.smo(), draft.submissionId(), id);
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId())))
                .as("one set of Findings and places, with the checks that did not complete marked, not two")
                .containsExactlyEntriesOf(openScapFindings());
    }
}
