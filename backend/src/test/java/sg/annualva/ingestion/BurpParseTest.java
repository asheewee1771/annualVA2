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
 * Stories 20d, 21, 25, 28 and 30: a Burp Suite XML issue export uploaded as the Burp Suite Scan Report is read into Findings,
 * one per issue type, host and page, with an occurrence for each place on the page it was found, with the severity and the
 * confidence Burp gave (so that a Tentative issue can be marked False Positive later); the export time and the hosts are read
 * for the SMO/Tech Lead to confirm, and the date is flagged as the time of the export and not of the scan.
 */
class BurpParseTest extends BurpSeam1 {

    @Test
    void aBurpExportIsReadIntoOneFindingPerIssueTypeHostAndPageWithItsSeverityAndConfidence() {
        Draft draft = draft();

        JsonNode file = settledBurp(draft, "issues.xml", Samples.burpSample());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("BURP_XML");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK",
                "PARSED");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(4);
        assertThat(burpSummary(page)).containsExactlyEntriesOf(sampleFindings());
    }

    @Test
    void eachFindingCarriesItsReportTypeIssueNameHostAndPathAndSourceFileScopedToTheSystem() {
        Draft draft = draft();
        String id = settledBurp(draft, "issues.xml", Samples.burpSample()).get("id").asString();

        JsonNode page = findings(draft.smo(), draft.submissionId());

        JsonNode xss = byIdentifier(page, "Cross-site scripting (reflected)");
        assertThat(xss.get("reportType").asString()).isEqualTo(BURP_SUITE);
        assertThat(xss.get("identityKey").asString()).isEqualTo(XSS_KEY);
        assertThat(xss.get("packageOrRule").asString()).as("the page, by host and path").isEqualTo(APP + "/search");
        assertThat(xss.get("severity").asString()).isEqualTo("HIGH");
        assertThat(xss.get("confidence").asString()).as("the most sure of its two places, Certain and Firm")
                .isEqualTo("CERTAIN");
        assertThat(xss.get("occurrenceCount").asInt()).as("one place for each parameter it was found in").isEqualTo(2);
        assertThat(xss.get("didNotComplete").asBoolean()).isFalse();
        assertThat(xss.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(xss.get("sourceFiles")).hasSize(1);
        assertThat(xss.get("sourceFiles").get(0).get("id").asString()).isEqualTo(id);
        assertThat(xss.get("sourceFiles").get(0).get("name").asString()).isEqualTo("issues.xml");
        JsonNode sql = byIdentifier(page, "SQL injection");
        assertThat(sql.get("packageOrRule").asString()).as("the path without its query and path parameter")
                .isEqualTo(API + "/v1/items");
        assertThat(sql.get("confidence").asString()).isEqualTo("TENTATIVE");
    }

    @Test
    void theSeverityIsBurpsInAnyCaseInformationIsInformationalAndAnUnratedOrUnknownIssueIsMedium() {
        Draft draft = draft();
        Map<String, String> ratings = new LinkedHashMap<>();
        ratings.put("High", "HIGH");
        ratings.put("Medium", "MEDIUM");
        ratings.put("Low", "LOW");
        ratings.put("Information", "INFORMATIONAL");
        ratings.put("information", "INFORMATIONAL");
        ratings.put("  INFORMATION ", "INFORMATIONAL");
        ratings.put("HIGH", "HIGH");
        ratings.put("Info", "INFORMATIONAL");
        ratings.put("Critical", "CRITICAL");
        ratings.put("unheard-of", "MEDIUM");
        ratings.put("3", "MEDIUM");
        ratings.put("", "MEDIUM");
        ratings.put(null, "MEDIUM");
        List<String> issues = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        int n = 0;
        for (var rating : ratings.entrySet()) {
            String type = "t" + n++;
            issues.add(issue(type, "Issue " + type, APP, "/" + type, null, rating.getKey(), "Certain"));
            expected.put("burp|" + type + "|" + APP + "|/" + type, rating.getValue() + "/1/CERTAIN");
        }

        settledBurp(draft, "severity.xml", burp(issues.toArray(String[]::new)));

        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void theConfidenceIsCertainFirmOrTentativeInAnyCaseAndAnythingElseOrNoneIsNoConfidence() {
        Draft draft = draft();
        Map<String, String> confidences = new LinkedHashMap<>();
        confidences.put("Certain", "CERTAIN");
        confidences.put("Firm", "FIRM");
        confidences.put("Tentative", "TENTATIVE");
        confidences.put(" tentative ", "TENTATIVE");
        confidences.put("FIRM", "FIRM");
        confidences.put("Probable", "NONE");
        confidences.put("", "NONE");
        confidences.put(null, "NONE");
        List<String> issues = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        int n = 0;
        for (var confidence : confidences.entrySet()) {
            String type = "t" + n++;
            issues.add(issue(type, "Issue " + type, APP, "/" + type, null, "Low", confidence.getKey()));
            expected.put("burp|" + type + "|" + APP + "|/" + type, "LOW/1/" + confidence.getValue());
        }

        settledBurp(draft, "confidence.xml", burp(issues.toArray(String[]::new)));

        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void aFindingIsTentativeOnlyIfEveryPlaceItWasFoundAtWasAndAnUnsaidConfidenceDoesNotWeakenASaidOne() {
        Draft draft = draft();
        byte[] first = burp(
                issue("a", "A", APP, "/p", "/p [x]", "Low", "Tentative"), issue("a", "A", APP, "/p", "/p [y]", "Low", "Tentative"),
                issue("b", "B", APP, "/p", "/p [x]", "Low", "Tentative"), issue("b", "B", APP, "/p", "/p [y]", "Low", "Firm"),
                issue("c", "C", APP, "/p", "/p [x]", "Low", "Tentative"), issue("c", "C", APP, "/p", "/p [y]", "Low", null),
                issue("d", "D", APP, "/p", "/p [x]", "Low", null), issue("d", "D", APP, "/p", "/p [y]", "Low", null),
                issue("e", "E", APP, "/p", "/p [x]", "Low", "Tentative"), issue("e", "E", APP, "/p", "/p [x]", "Low", "Certain"));

        settledBurp(draft, "mixed.xml", first);

        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "burp|a|" + APP + "|/p", "LOW/2/TENTATIVE", "burp|b|" + APP + "|/p", "LOW/2/FIRM",
                "burp|c|" + APP + "|/p", "LOW/2/TENTATIVE", "burp|d|" + APP + "|/p", "LOW/2/NONE",
                "burp|e|" + APP + "|/p", "LOW/1/CERTAIN"));
    }

    @Test
    void severalFilesOfOneScanReportMakeOneFindingPerKeyWithAnOccurrenceForEachFileTheWorstSeverityAndTheMostSureConfidence() {
        Draft draft = draft();
        settledBurp(draft, "first.xml", burp(issue("x", "X", APP, "/p", "/p [a]", "Low", "Tentative"),
                issue("y", "Y", APP, "/q", "/q", "Medium", "Firm")));
        settledBurp(draft, "second.xml", burp(issue("x", "X", APP, "/p", "/p [a]", "High", "Firm"),
                issue("z", "Z", API, "/", "/", "Information", null)));

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(burpSummary(page)).containsExactlyInAnyOrderEntriesOf(Map.of("burp|x|" + APP + "|/p", "HIGH/2/FIRM",
                "burp|y|" + APP + "|/q", "MEDIUM/1/FIRM", "burp|z|" + API + "|/", "INFORMATIONAL/1/NONE"));
        assertThat(sourceNames(byIdentifier(page, "X"))).containsExactly("first.xml", "second.xml");
        assertThat(sourceNames(byIdentifier(page, "Y"))).containsExactly("first.xml");
        assertThat(sourceNames(byIdentifier(page, "Z"))).containsExactly("second.xml");
    }

    @Test
    void theSameIssueTypeOnTheSamePageIsOneFindingWithAPlaceForEachLocationAndAnotherPageHostOrTypeIsAnother() {
        Draft draft = draft();
        byte[] report = burp(
                issue("t", "T", APP, "/page", "/page [a]", "Low", "Certain"), issue("t", "T", APP, "/page", "/page [b]", "Low", "Certain"),
                issue("t", "T", APP, "/page", "/page [b]", "Medium", "Certain"),
                issue("t", "T", APP, "/page", null, "Low", "Certain"), issue("t", "T", APP, "/page", null, "Low", "Certain"),
                issue("t", "T", APP, "/other", "/other", "Low", "Certain"), issue("t", "T", API, "/page", "/page", "Low", "Certain"),
                issue("u", "T", APP, "/page", "/page", "Low", "Certain"));

        settledBurp(draft, "pages.xml", report);

        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "burp|t|" + APP + "|/page", "MEDIUM/3/CERTAIN", "burp|t|" + APP + "|/other", "LOW/1/CERTAIN",
                "burp|t|" + API + "|/page", "LOW/1/CERTAIN", "burp|u|" + APP + "|/page", "LOW/1/CERTAIN"));
    }

    @Test
    void thePathIsNormalisedSoThatTheSamePageIsTheSameFindingInTheNextScan() {
        Draft draft = draft();
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("/search", "/search");
        paths.put("/search?q=1&r=2", "/search");
        paths.put("/search?q=1#top", "/search");
        paths.put("/search#top", "/search");
        paths.put("/search;jsessionid=ABC123", "/search");
        paths.put("/a;x=1/b;y=2?z", "/a/b");
        paths.put("//search//", "/search");
        paths.put("/search/", "/search");
        paths.put("search", "/search");
        paths.put("/a%2fb%2Fc", "/a%2Fb%2Fc");
        paths.put("/Search", "/Search");
        paths.put("/", "/");
        paths.put("///", "/");
        paths.put("?q=1", "/");
        paths.put("  ", "/");
        paths.put(null, "/");
        List<String> issues = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        int n = 0;
        for (var path : paths.entrySet()) {
            String type = "t" + n++;
            issues.add(issue(type, "Issue", APP, path.getKey(), null, "Low", "Certain"));
            expected.put("burp|" + type + "|" + APP + "|" + path.getValue(), "LOW/1/CERTAIN");
        }
        // The same type and host, written six ways: one Finding.
        for (String way : new String[] {"/same", "/same/", "/same?x=1", "/same;a=b", "//same", "/same#f"}) {
            issues.add(issue("same", "Same", APP, way, way, "Low", "Certain"));
        }
        expected.put("burp|same|" + APP + "|/same", "LOW/6/CERTAIN");

        settledBurp(draft, "paths.xml", burp(issues.toArray(String[]::new)));

        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void theHostIsKeyedInLowerCaseSoOneSiteWrittenTwoWaysIsOneFinding() {
        Draft draft = draft();
        byte[] report = burp(issue("t", "T", "HTTPS://Throwaway-App.INVALID", "/p", "/p [a]", "Low", "Certain"),
                issue("t", "T", APP, "/p", "/p [b]", "Low", "Certain"));

        settledBurp(draft, "hosts.xml", report);

        assertThat(burpSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(
                Map.entry("burp|t|" + APP + "|/p", "LOW/2/CERTAIN"));
    }

    @Test
    void theIssueNameIsShownAndTheTypeIdIsShownWhenThereIsNone() {
        Draft draft = draft();
        byte[] report = burp(issue("named", "A name", APP, "/p", null, "Low", "Certain"),
                issue("unnamed", null, APP, "/p", null, "Low", "Certain"));

        settledBurp(draft, "names.xml", report);

        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(byIdentifier(page, "A name").get("identityKey").asString()).isEqualTo("burp|named|" + APP + "|/p");
        assertThat(byIdentifier(page, "unnamed").get("identityKey").asString()).isEqualTo("burp|unnamed|" + APP + "|/p");
    }

    @Test
    void theExportTimeIsTheScanDateFlaggedAsTheExportTimeAndTheHostsTheIssuesNameAreTheTarget() {
        Draft draft = draft();
        String sample = settledBurp(draft, "issues.xml", Samples.burpSample()).get("id").asString();
        String single = settledBurp(draft, "single.xml", burpAt("Tue Jul 20 10:00:00 UTC 2027",
                issue("t", "T", "https://only-host.invalid", "/", "/", "Low", "Certain"))).get("id").asString();

        JsonNode both = metadata(draft.smo(), draft.submissionId(), sample);
        JsonNode one = metadata(draft.smo(), draft.submissionId(), single);

        assertThat(both.get("target").asString()).isEqualTo(APP + ", " + API);
        assertThat(both.get("scanDate").asString()).as("10:00 in SGT, which is eight hours ahead of UTC")
                .startsWith("2027-07-20T02:00:00");
        assertThat(both.get("scanDateIsExportTime").asBoolean()).isTrue();
        assertThat(one.get("target").asString()).isEqualTo("https://only-host.invalid");
        assertThat(one.get("scanDate").asString()).startsWith("2027-07-20T10:00:00");
        assertThat(one.get("scanDateIsExportTime").asBoolean()).isTrue();
    }

    @Test
    void aDateOfAnotherFormatIsNotFlaggedAsTheExportTime() {
        Draft draft = draft();
        String id = settled(draft, DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();

        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);

        assertThat(metadata.get("scanDate").isNull()).isFalse();
        assertThat(metadata.get("scanDateIsExportTime").asBoolean()).as("only a Burp export has just its export time").isFalse();
    }

    @Test
    void anExportWithNoIssuesIsAcceptedWithNoFindingsAndStillOffersItsExportTime() {
        Draft draft = draft();

        JsonNode file = settledBurp(draft, "empty.xml", burp());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), file.get("id").asString());
        assertThat(metadata.get("target").isNull()).isTrue();
        assertThat(metadata.get("scanDate").asString()).startsWith("2027-07-20T02:00:00");
    }

    @Test
    void aFindingOfAnotherScanReportHasNoConfidence() {
        Draft draft = draft();
        settled(draft, DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckSampleXml());
        settled(draft, SECURE_CONFIGURATION, "audit.nessus", Samples.nessusComplianceSample());

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(page.get("total").asInt()).isPositive();
        for (JsonNode finding : page.get("items")) {
            assertThat(finding.get("confidence").isNull()).as(finding.get("identityKey").asString()).isTrue();
        }
    }

    @Test
    void aFileIsNeverParsedWithoutItsFindingsOrTheOtherWayRound() {
        Draft draft = draft();
        String id = uploaded(draft.smo(), draft.submissionId(), BURP_SUITE, "issues.xml", Samples.burpSample()).get("id")
                .asString();
        pass();
        // The write of the places fails (the last write of the settlement, after the file row, its stage rows and the
        // Findings), and then the commit of the settlement fails (after every write of it). The first catches Findings kept
        // after the settlement, the second Findings kept in a transaction of their own.
        for (String fault : new String[] {"on", "late"}) {
            arrange.findingFault(fault);
            try {
                pass();

                JsonNode file = status(draft.smo(), draft.submissionId(), id);
                assertThat(file.get("status").asString()).as(fault + ": the whole outcome was rolled back")
                        .isEqualTo("SCAN_SUBMITTED");
                assertThat(stages(file)).as(fault + ": no stage of the failed attempt was kept")
                        .containsExactly("QUARANTINED", "SCAN_SUBMITTED");
                assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt())
                        .as(fault + ": no Finding was kept").isZero();
                assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.CLEAN)).as(fault + ": nor a clean copy")
                        .isZero();
                assertThat(draft.smo().get(fileAt(draft.submissionId(), id) + "/metadata").status())
                        .as(fault + ": nor what the file said about the scan").isEqualTo(404);
            } finally {
                arrange.findingFault("off");
            }
        }

        pass();

        assertThat(status(draft.smo(), draft.submissionId(), id).get("status").asString()).isEqualTo("PARSED");
        assertThat(burpSummary(findings(draft.smo(), draft.submissionId())))
                .as("one set of Findings and places, with their confidence, not two")
                .containsExactlyEntriesOf(sampleFindings());
    }

    @Test
    void theSampleExportCarriesAProvenanceNote() {
        assertThat(new String(Samples.burpSample(), java.nio.charset.StandardCharsets.UTF_8)).contains("PROVENANCE")
                .contains("NOT the output of a real scan").contains("Regenerate it from a real scan");
    }
}
