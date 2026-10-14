package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 20c, 21, 25, 28 and 29: a {@code .nessus} compliance audit uploaded as the Secure Configuration Scan Report is read
 * into Findings for the checks that FAILED, drew a WARNING or gave an ERROR (a check that did not complete), one Finding per
 * check and one occurrence per host; PASSED checks and every item that is not a compliance check are ignored; the latest
 * {@code HOST_END} and the hosts are read for the SMO/Tech Lead to confirm.
 */
class NessusParseTest extends NessusSeam1 {

    @Test
    void aNessusComplianceAuditIsReadIntoFindingsForFailedWarnedAndErroredChecks() {
        Draft draft = draft();

        JsonNode file = settledNessus(draft, "audit.nessus", Samples.nessusComplianceSample());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("NESSUS");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK",
                "PARSED");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(5);
        assertThat(findingSummary(page)).containsExactlyEntriesOf(sampleFindings());
    }

    @Test
    void eachFindingCarriesItsReportTypeCheckSeverityAndSourceFileScopedToTheSystem() {
        Draft draft = draft();
        String id = settledNessus(draft, "audit.nessus", Samples.nessusComplianceSample()).get("id").asString();

        JsonNode page = findings(draft.smo(), draft.submissionId());

        JsonNode partition = byIdentifier(page, "1.1.2 Ensure /tmp is a separate partition");
        assertThat(partition.get("reportType").asString()).isEqualTo(SECURE_CONFIGURATION);
        assertThat(partition.get("identityKey").asString()).as("keyed on the check id").isEqualTo("nessus|" + TMP_PARTITION);
        assertThat(partition.get("packageOrRule").asString()).as("the check, by its name")
                .isEqualTo("1.1.2 Ensure /tmp is a separate partition");
        assertThat(partition.get("severity").asString()).isEqualTo("HIGH");
        assertThat(partition.get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(partition.get("didNotComplete").asBoolean()).isFalse();
        assertThat(partition.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(partition.get("sourceFiles")).hasSize(1);
        assertThat(partition.get("sourceFiles").get(0).get("id").asString()).isEqualTo(id);
        assertThat(partition.get("sourceFiles").get(0).get("name").asString()).isEqualTo("audit.nessus");
        assertThat(byIdentifier(page, "6.1.2 Ensure permissions on /etc/passwd are configured").get("didNotComplete")
                .asBoolean()).isTrue();
    }

    @Test
    void passedChecksAndItemsThatAreNotComplianceChecksAreIgnoredAndSoIsAnyResultThatIsNotFailedWarnedOrErrored() {
        Draft draft = draft();
        byte[] report = nessus(host("host-a", null,
                check("id-passed", "Passed", "PASSED", "0"), notACheck("4"), notACheck("3"), notACheck("0"),
                check("id-skipped", "Skipped", "SKIPPED", "3"), check("id-unheard-of", "Odd", "unheard-of", "3"),
                check("id-empty", "Empty", "", "3"), check("id-no-result", "No result", null, "3"),
                check("id-failed", "Failed", "FAILED", "3"), check("id-warning", "Warning", "WARNING", "3"),
                check("id-error", "Error", "ERROR", "3"), check("id-lower", "Lower case", "failed", "3"),
                check("id-padded", "Padded", " Warning ", "3")));

        JsonNode file = settledNessus(draft, "results.nessus", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "nessus|id-failed", "HIGH/1", "nessus|id-warning", "HIGH/1", "nessus|id-error", "HIGH/1/INCOMPLETE",
                "nessus|id-lower", "HIGH/1", "nessus|id-padded", "HIGH/1"));
    }

    @Test
    void theSeverityIsTheOneTheItemCarriesInsideNessusScaleOrWordsAndAnUnratedCheckIsMedium() {
        Draft draft = draft();
        Map<String, String> ratings = new LinkedHashMap<>();
        ratings.put("0", "INFORMATIONAL");
        ratings.put("1", "LOW");
        ratings.put("2", "MEDIUM");
        ratings.put("3", "HIGH");
        ratings.put("4", "CRITICAL");
        ratings.put(" 4 ", "CRITICAL");
        ratings.put("Critical", "CRITICAL");
        ratings.put("high", "HIGH");
        ratings.put("Info", "INFORMATIONAL");
        ratings.put("None", "INFORMATIONAL");
        ratings.put("5", "MEDIUM");
        ratings.put("-1", "MEDIUM");
        ratings.put("", "MEDIUM");
        ratings.put("unheard-of", "MEDIUM");
        ratings.put(null, "MEDIUM");
        List<String> items = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        int n = 0;
        for (var rating : ratings.entrySet()) {
            String id = "c" + n++;
            items.add(check(id, "Check " + id, "FAILED", rating.getKey()));
            expected.put("nessus|" + id, rating.getValue() + "/1");
        }

        settledNessus(draft, "severity.nessus", nessus(host("host-a", null, items.toArray(String[]::new))));

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void aCheckIsKeyedOnItsIdElseItsNameAndOneWithNeitherIsNotAReport() {
        Draft draft = draft();
        byte[] report = nessus(
                host("host-a", null, check("id-1", "Same name", "FAILED", "3"), check("id-2", "Same name", "FAILED", "3"),
                        check(null, "Only a name", "FAILED", "3"), check("only-an-id", null, "FAILED", "3"),
                        check("id-3", "Name on host a", "FAILED", "3")),
                host("host-b", null, check("id-3", "Name on host b", "FAILED", "2"), check(null, "Only a name", "ERROR", "3")));

        settledNessus(draft, "keys.nessus", report);

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(findingSummary(page)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "nessus|id-1", "HIGH/1", "nessus|id-2", "HIGH/1", "nessus|Only a name", "HIGH/2",
                "nessus|only-an-id", "HIGH/1", "nessus|id-3", "HIGH/2"));
        assertThat(byIdentifier(page, "only-an-id").get("packageOrRule").asString()).as("shown by its id when it has no name")
                .isEqualTo("only-an-id");
        assertThat(page.get("items").size()).as("two checks with one name are two Findings, one id with two names is one")
                .isEqualTo(5);
    }

    @Test
    void aCheckThatFailedOnOneHostAndGaveAnErrorOnAnotherIsAFailedCheckAndOneThatErroredEverywhereDidNotComplete() {
        Draft draft = draft();
        byte[] report = nessus(
                host("host-a", null, check("mixed", "Mixed", "FAILED", "2"), check("everywhere", "Everywhere", "ERROR", "2")),
                host("host-b", null, check("mixed", "Mixed", "ERROR", "2"), check("everywhere", "Everywhere", "ERROR", "2")));

        settledNessus(draft, "two-hosts.nessus", report);

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "nessus|mixed", "MEDIUM/2", "nessus|everywhere", "MEDIUM/2/INCOMPLETE"));
    }

    @Test
    void aCheckResultedTwiceForOneHostIsOnePlaceThatDidNotCompleteOnlyIfEveryResultDidNot() {
        Draft draft = draft();
        byte[] report = nessus(host("host-a", null,
                check("fail-then-error", "A", "FAILED", "3"), check("fail-then-error", "A", "ERROR", "1"),
                check("error-then-warn", "B", "ERROR", "1"), check("error-then-warn", "B", "WARNING", "2"),
                check("errors", "C", "ERROR", "1"), check("errors", "C", "ERROR", "2")));

        settledNessus(draft, "twice.nessus", report);

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "nessus|fail-then-error", "HIGH/1", "nessus|error-then-warn", "MEDIUM/1",
                "nessus|errors", "MEDIUM/1/INCOMPLETE"));
    }

    @Test
    void aHostScannedTwiceInOneFileIsOnePlacePerCheck() {
        Draft draft = draft();
        byte[] report = nessus(host("host-a", null, check("c1", "One", "FAILED", "1")),
                host("host-a", null, check("c1", "One", "ERROR", "3"), check("c2", "Two", "ERROR", "2")));

        settledNessus(draft, "twice.nessus", report);

        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "nessus|c1", "HIGH/1", "nessus|c2", "MEDIUM/1/INCOMPLETE"));
    }

    @Test
    void theScanDateIsTheLatestHostEndAndTheTargetIsTheHostsTheFileNames() {
        Draft draft = draft();
        String sample = settledNessus(draft, "audit.nessus", Samples.nessusComplianceSample()).get("id").asString();
        String single = settledNessus(draft, "single.nessus",
                nessus(host("only-host", "Tue Jul 20 10:00:00 2027", check("c", "C", "FAILED", "3")))).get("id").asString();

        JsonNode both = metadata(draft.smo(), draft.submissionId(), sample);
        JsonNode one = metadata(draft.smo(), draft.submissionId(), single);

        assertThat(both.get("target").asString()).isEqualTo("throwaway-host-04, throwaway-host-05");
        assertThat(both.get("scanDate").asString()).as("the later of the two, 11:20:40 (taken as UTC)")
                .startsWith("2027-07-20T11:20:40");
        assertThat(one.get("target").asString()).isEqualTo("only-host");
        assertThat(one.get("scanDate").asString()).startsWith("2027-07-20T10:00:00");
    }

    @Test
    void onlyHostEndGivesTheScanDateNotHostStartAndAFileWithNoneOffersNoDate() {
        Draft draft = draft();
        String startOnly = "<ReportHost name=\"host-a\"><HostProperties><tag name=\"HOST_START\">Tue Jul 20 12:00:00 2027</tag>"
                + "<tag name=\"HOST_END\">Tue Jul 20 09:00:00 2027</tag></HostProperties></ReportHost>";
        String none = "<ReportHost name=\"host-b\"><HostProperties><tag name=\"HOST_START\">Tue Jul 20 12:00:00 2027</tag>"
                + "</HostProperties></ReportHost>";

        String first = settledNessus(draft, "a.nessus", nessus(startOnly)).get("id").asString();
        String second = settledNessus(draft, "b.nessus", nessus(none)).get("id").asString();

        assertThat(metadata(draft.smo(), draft.submissionId(), first).get("scanDate").asString())
                .startsWith("2027-07-20T09:00:00");
        assertThat(metadata(draft.smo(), draft.submissionId(), second).get("scanDate").isNull()).isTrue();
    }

    @Test
    void severalFilesOfOneScanReportMakeOneFindingPerCheckWithAnOccurrenceForEachFileAndTheWorstSeverity() {
        Draft draft = draft();
        settledNessus(draft, "first.nessus", nessus(host("host-a", null, check("x", "X", "FAILED", "1"),
                check("y", "Y", "FAILED", "2"))));
        settledNessus(draft, "second.nessus", nessus(host("host-b", null, check("x", "X", "ERROR", "3"),
                check("z", "Z", "ERROR", "1"))));

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(findingSummary(page)).containsExactlyInAnyOrderEntriesOf(Map.of("nessus|x", "HIGH/2",
                "nessus|y", "MEDIUM/1", "nessus|z", "LOW/1/INCOMPLETE"));
        assertThat(sourceNames(byIdentifier(page, "X"))).containsExactly("first.nessus", "second.nessus");
        assertThat(sourceNames(byIdentifier(page, "Y"))).containsExactly("first.nessus");
        assertThat(sourceNames(byIdentifier(page, "Z"))).containsExactly("second.nessus");
    }

    @Test
    void aNessusAuditAndAScapResultsFileOfOneScanReportAreListedTogetherWithTheirOwnKeys() {
        Draft draft = draft();
        settledNessus(draft, "audit.nessus", Samples.nessusComplianceSample());
        settledNessus(draft, "cis.xml", Samples.cisCatXccdfSample());

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(page.get("total").asInt()).isEqualTo(9);
        int nessus = 0;
        int scap = 0;
        for (JsonNode finding : page.get("items")) {
            assertThat(finding.get("reportType").asString()).isEqualTo(SECURE_CONFIGURATION);
            String key = finding.get("identityKey").asString();
            nessus += key.startsWith("nessus|") ? 1 : 0;
            scap += key.startsWith("scap|") ? 1 : 0;
        }
        assertThat(nessus).isEqualTo(5);
        assertThat(scap).isEqualTo(4);
    }

    @Test
    void anAuditWhereEveryCheckPassedOrAScanWithNoComplianceItemsIsAcceptedWithNoFindings() {
        Draft draft = draft();

        JsonNode passed = settledNessus(draft, "clean.nessus", nessus(host("host-a", "Tue Jul 20 10:00:00 2027",
                check("c1", "One", "PASSED", "0"), notACheck("4"))));
        JsonNode minimal = settledNessus(draft, "minimal.nessus", Samples.nessus());

        assertThat(passed.get("status").asString()).isEqualTo("PARSED");
        assertThat(minimal.get("status").asString()).isEqualTo("PARSED");
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), passed.get("id").asString());
        assertThat(metadata.get("target").asString()).isEqualTo("host-a");
        assertThat(metadata.get("scanDate").asString()).startsWith("2027-07-20T10:00:00");
    }

    @Test
    void aFileIsNeverParsedWithoutItsFindingsOrTheOtherWayRound() {
        Draft draft = draft();
        String id = uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, "audit.nessus",
                Samples.nessusComplianceSample()).get("id").asString();
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
                .containsExactlyEntriesOf(sampleFindings());
    }
}
