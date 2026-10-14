package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 20a, 21 and 25: how a Dependency-Check rating becomes one of the five severities (CVSS v3, then v2, an unrated
 * vulnerability High), how a report that is not the shape of the format is refused as REJECTED_SCHEMA with nothing kept, how
 * the dates and names read from the file are cleaned, and that no XML reader obeys a DTD or an entity.
 */
class DependencyCheckRulesTest extends DependencyCheckSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";

    private static void addSeverityCase(List<String> vulnerabilities, Map<String, String> expected, String name,
            String ratings, String severity) {
        vulnerabilities.add(jsonVulnerability(name, ratings));
        expected.put("dc|" + name + "|" + PURL_TEXT, severity + "/1");
    }

    @Test
    void theSeverityComesFromCvssV3ThenV2AndAnUnratedVulnerabilityIsHigh() {
        Draft draft = draft();
        List<String> vulnerabilities = new java.util.ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        // CVSS v3 bands: 9.0 and over Critical, 7.0 High, 4.0 Medium, above 0 Low, 0.0 None (Informational)
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0001", v3("10.0"), "CRITICAL");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0002", v3("9.0"), "CRITICAL");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0003", v3("8.9"), "HIGH");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0004", v3("7.0"), "HIGH");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0005", v3("6.9"), "MEDIUM");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0006", v3("4.0"), "MEDIUM");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0007", v3("3.9"), "LOW");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0008", v3("0.1"), "LOW");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0009", v3("0.0"), "INFORMATIONAL");
        // CVSS v2 has no Critical band: 7.0 and over is High, 4.0 Medium, the rest Low
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0010", v2("10.0"), "HIGH");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0011", v2("7.0"), "HIGH");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0012", v2("6.9"), "MEDIUM");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0013", v2("4.0"), "MEDIUM");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0014", v2("3.9"), "LOW");
        // v3 wins over v2 whichever is higher
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0015", v3("3.0") + "," + v2("9.0"), "LOW");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0016", v3("9.8") + "," + v2("2.0"), "CRITICAL");
        // nobody rated it: not assumed harmless
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0017", "", "HIGH");
        // a score that is not a number, or not a score, never decides: the next rating does
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0018", "\"cvssv3\":{\"baseScore\":\"n/a\"}," + v2("5.0"),
                "MEDIUM");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0019", v3("99") + "," + v2("5.0"), "MEDIUM");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0020", v3("-1") + "," + v2("2.0"), "LOW");
        // the tool's own word, when it wrote no usable score
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0021", "\"cvssv3\":{\"baseSeverity\":\"CRITICAL\"}", "CRITICAL");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0022", "\"cvssv2\":{\"severity\":\"low\"}", "LOW");
        addSeverityCase(vulnerabilities, expected, "CVE-2027-0023", "\"cvssv3\":{\"baseSeverity\":\"UNHEARD-OF\"}",
                "HIGH");
        byte[] report = jsonReport(jsonDependency("commons-text-1.9.jar", "/opt/a/commons-text-1.9.jar", PURL_TEXT,
                vulnerabilities.toArray(String[]::new)));

        settledDc(draft, "severity.json", report);

        Map<String, String> actual = summary(findings(draft.smo(), draft.submissionId()));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void theSameRatingsInXmlGiveTheSameSeverities() {
        Draft draft = draft();
        String body = "<dependencies><dependency><fileName>a.jar</fileName><filePath>/a.jar</filePath>"
                + "<identifiers><package><id>" + PURL_TEXT + "</id></package></identifiers><vulnerabilities>"
                + "<vulnerability><name>CVE-2027-1</name><cvssV3><baseScore>9.0</baseScore></cvssV3></vulnerability>"
                + "<vulnerability><name>CVE-2027-2</name><cvssV3><baseScore>3.0</baseScore></cvssV3>"
                + "<cvssV2><score>9.0</score></cvssV2></vulnerability>"
                + "<vulnerability><name>CVE-2027-3</name><cvssV2><score>10.0</score></cvssV2></vulnerability>"
                + "<vulnerability><name>CVE-2027-4</name></vulnerability>"
                + "<vulnerability><name>CVE-2027-5</name><cvssV3><baseScore>0.0</baseScore></cvssV3></vulnerability>"
                + "</vulnerabilities></dependency></dependencies>";

        settledDc(draft, "severity.xml", xmlReport(body));

        assertThat(summary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "dc|CVE-2027-1|" + PURL_TEXT, "CRITICAL/1", "dc|CVE-2027-2|" + PURL_TEXT, "LOW/1",
                "dc|CVE-2027-3|" + PURL_TEXT, "HIGH/1", "dc|CVE-2027-4|" + PURL_TEXT, "HIGH/1",
                "dc|CVE-2027-5|" + PURL_TEXT, "INFORMATIONAL/1"));
    }

    @Test
    void aFindingTakesTheHighestSeverityOfItsOccurrences() {
        Draft draft = draft();
        byte[] report = jsonReport(
                jsonDependency("a.jar", "/one/a.jar", PURL_TEXT, jsonVulnerability("CVE-2027-7", v3("5.0"))),
                jsonDependency("a.jar", "/two/a.jar", PURL_TEXT, jsonVulnerability("CVE-2027-7", v3("9.5"))),
                jsonDependency("a.jar", "/three/a.jar", PURL_TEXT, jsonVulnerability("CVE-2027-7", v3("2.0"))));

        settledDc(draft, "occurrences.json", report);

        assertThat(summary(findings(draft.smo(), draft.submissionId())))
                .containsExactly(Map.entry("dc|CVE-2027-7|" + PURL_TEXT, "CRITICAL/3"));
    }

    @Test
    void anExportThatIsNotInTheShapeOfTheFormatIsRejectedAsSchemaAndNothingIsKept() {
        Draft draft = draft();
        Map<String, byte[]> hostile = new LinkedHashMap<>();
        hostile.put("no-namespace.xml", utf8("<analysis><dependencies/></analysis>"));
        hostile.put("wrong-namespace.xml",
                utf8("<analysis xmlns=\"http://example.invalid/x\"><dependencies/></analysis>"));
        hostile.put("no-dependencies.xml", xmlReport("<scanInfo/><projectInfo><name>p</name></projectInfo>"));
        hostile.put("nameless.xml", xmlReport("<dependencies><dependency><fileName>a.jar</fileName><vulnerabilities>"
                + "<vulnerability><cvssV3><baseScore>5.0</baseScore></cvssV3></vulnerability>"
                + "</vulnerabilities></dependency></dependencies>"));
        hostile.put("markup-in-a-leaf.xml", xmlReport("<dependencies><dependency><fileName><b>a</b></fileName>"
                + "</dependency></dependencies>"));
        hostile.put("nameless-dependency.xml", xmlReport("<dependencies><dependency><vulnerabilities><vulnerability>"
                + "<name>CVE-2027-1</name></vulnerability></vulnerabilities></dependency></dependencies>"));
        hostile.put("dependency-not-an-object.json", utf8("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":[1]}"));
        hostile.put("vulnerabilities-not-a-list.json", jsonReport("{\"fileName\":\"a.jar\",\"vulnerabilities\":{}}"));
        hostile.put("nameless.json", jsonReport(jsonDependency("a.jar", "/a.jar", PURL_TEXT, "{\"source\":\"NVD\"}")));
        hostile.put("project-info-not-an-object.json",
                utf8("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"projectInfo\":[],\"dependencies\":[]}"));
        hostile.put("bar-in-the-name.json", jsonReport(jsonDependency("a.jar", "/a.jar", PURL_TEXT,
                jsonVulnerability("CVE-2027-1|pkg:maven/other/lib@1", v3("5.0")))));
        hostile.put("long-name.json", jsonReport(jsonDependency("a.jar", "/a.jar", PURL_TEXT,
                jsonVulnerability("CVE-2027-" + "9".repeat(100), v3("5.0")))));
        hostile.put("long-package.json", jsonReport(jsonDependency("a.jar", "/a.jar", "pkg:maven/" + "x".repeat(500),
                jsonVulnerability("CVE-2027-1", v3("5.0")))));

        for (var entry : hostile.entrySet()) {
            JsonNode file = settledDc(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).isEqualTo("REJECTED_SCHEMA");
            assertThat(file.get("rejectCode").asString()).isEqualTo("SCHEMA");
            assertThat(stages(file)).as(entry.getKey())
                    .containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "REJECTED_SCHEMA");
            assertThat(draft.smo().get(fileAt(draft.submissionId(), file.get("id").asString()) + "/metadata").status())
                    .isEqualTo(404);
            assertThat(draft.smo().download(fileAt(draft.submissionId(), file.get("id").asString()) + "/content").status())
                    .as("a rejected file is never served").isEqualTo(404);
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).as("no Finding of a rejected file")
                .isZero();
        assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.CLEAN)).as("no clean copy of a rejected file")
                .isZero();
    }

    @Test
    void aRejectionNeverEchoesWhatTheFileSaid() {
        Draft draft = draft();
        byte[] report = jsonReport(jsonDependency(SENTINEL + ".jar", "/" + SENTINEL + "/a.jar", PURL_TEXT,
                jsonVulnerability("CVE-2027-1|" + SENTINEL, v3("5.0"))));

        JsonNode file = settledDc(draft, "x.json", report);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(file.toString()).doesNotContain(SENTINEL);
        assertThat(draft.smo().get(filesOf(draft.submissionId())).body()).doesNotContain(SENTINEL);
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain(SENTINEL);
    }

    @Test
    void aDtdAndItsEntitiesAreNeverObeyed() {
        Draft draft = draft();
        String external = "<!DOCTYPE analysis [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>";
        String internal = "<!DOCTYPE analysis [<!ENTITY cve \"CVE-2027-4242\">]>";
        String bomb = "<!DOCTYPE analysis [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]>";
        Map<String, byte[]> documents = new LinkedHashMap<>();
        documents.put("xxe.xml", utf8("<?xml version=\"1.0\"?>" + external + "<analysis xmlns=\"" + DC_NAMESPACE
                + "\"><projectInfo><name>&xxe;</name></projectInfo><dependencies/></analysis>"));
        documents.put("internal.xml", utf8("<?xml version=\"1.0\"?>" + internal + "<analysis xmlns=\"" + DC_NAMESPACE
                + "\"><dependencies><dependency><fileName>a.jar</fileName><vulnerabilities><vulnerability>"
                + "<name>&cve;</name></vulnerability></vulnerabilities></dependency></dependencies></analysis>"));
        documents.put("bomb.xml", utf8("<?xml version=\"1.0\"?>" + bomb + "<analysis xmlns=\"" + DC_NAMESPACE
                + "\"><projectInfo><name>&c;</name></projectInfo><dependencies/></analysis>"));

        for (var entry : documents.entrySet()) {
            JsonNode file = settledDc(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).startsWith("REJECTED_");
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain("CVE-2027-4242");
    }

    @Test
    void textReadFromTheReportLosesControlAndDirectionCharactersAndIsCutToAFixedLength() {
        Draft draft = draft();
        String longName = "p".repeat(300);
        byte[] report = jsonReportOf("{\"name\":\"  a\\u202Eb\\nc\\u0000d  \",\"reportDate\":\"2027-07-20T10:00:00Z\"}",
                jsonDependency("a\\nb.jar", "/opt/\\u202Ea.jar", "pkg:maven/example/a\\u202E@1",
                        jsonVulnerability("CVE-2027-\\u202E1", v3("5.0"))));
        byte[] long300 = jsonReportOf("{\"name\":\"" + longName + "\"}");

        String cleaned = settledDc(draft, "clean.json", report).get("id").asString();
        String cut = settledDc(draft, "cut.json", long300).get("id").asString();

        assertThat(metadata(draft.smo(), draft.submissionId(), cleaned).get("target").asString()).isEqualTo("abcd");
        assertThat(metadata(draft.smo(), draft.submissionId(), cut).get("target").asString()).hasSize(200);
        assertThat(summary(findings(draft.smo(), draft.submissionId())))
                .containsExactly(Map.entry("dc|CVE-2027-1|pkg:maven/example/a@1", "MEDIUM/1"));
    }

    @Test
    void theScanDateIsReadInEveryFormTheToolWritesAndIsNullWhenTheFileSaysNothingUsable() {
        Draft draft = draft();
        Map<String, String> dates = new LinkedHashMap<>();
        dates.put("\"reportDate\":\"2027-07-20T10:00:00Z\"", "2027-07-20T10:00:00");
        dates.put("\"reportDate\":\"2027-07-20T10:00:00.123456+08:00\"", "2027-07-20T02:00:00");
        dates.put("\"reportDate\":\"2027-07-20T10:00:00.123+0800\"", "2027-07-20T02:00:00");
        dates.put("\"reportDate\":\"2027-07-20T10:00:00\"", "2027-07-20T10:00:00");
        dates.put("\"reportDate\":\"last tuesday\"", null);
        dates.put("\"reportDate\":\"+999999999-01-01T00:00:00Z\"", null);
        dates.put("\"reportDate\":\"1850-01-01T00:00:00Z\"", null);
        dates.put("\"reportDate\":12345", null);
        dates.put("\"other\":1", null);
        for (var entry : dates.entrySet()) {
            String id = settledDc(draft, "d.json", jsonReportOf("{\"name\":\"p\"," + entry.getKey() + "}"))
                    .get("id").asString();

            JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);

            if (entry.getValue() == null) {
                assertThat(metadata.get("scanDate").isNull()).as(entry.getKey()).isTrue();
            } else {
                assertThat(metadata.get("scanDate").asString()).as(entry.getKey()).startsWith(entry.getValue());
            }
            assertThat(metadata.get("target").asString()).isEqualTo("p");
        }
    }

    @Test
    void aVeryLongTextTheParserDoesNotReadIsNoProblem() {
        Draft draft = draft();
        String longText = "d".repeat(50_000);
        byte[] json = jsonReport("{\"fileName\":\"a.jar\",\"filePath\":\"/a.jar\",\"description\":\"" + longText
                + "\",\"packages\":[{\"id\":\"" + PURL_TEXT + "\"}],\"vulnerabilities\":[{\"name\":\"CVE-2027-1\","
                + "\"description\":\"" + longText + "\"," + v3("5.0") + "}]}");
        byte[] xml = xmlReport("<dependencies><dependency><fileName>a.jar</fileName><filePath>/a.jar</filePath>"
                + "<description>" + longText + "</description><identifiers><package><id>" + PURL_TEXT
                + "</id></package></identifiers><vulnerabilities><vulnerability><name>CVE-2027-1</name>"
                + "<description>" + longText + "</description><cvssV3><baseScore>5.0</baseScore></cvssV3>"
                + "</vulnerability></vulnerabilities></dependency></dependencies>");

        JsonNode first = settledDc(draft, "long.json", json);
        JsonNode second = settledDc(draft, "long.xml", xml);

        assertThat(first.get("status").asString()).isEqualTo("PARSED");
        assertThat(second.get("status").asString()).isEqualTo("PARSED");
        assertThat(summary(findings(draft.smo(), draft.submissionId()))).as("one place in each of the two files")
                .containsExactly(Map.entry("dc|CVE-2027-1|" + PURL_TEXT, "MEDIUM/2"));
    }

    @Test
    void aValueThatIsReadAndFarLongerThanAnyRealOneIsRefusedInXmlAndJsonAlike() {
        Draft draft = draft();
        String longName = "p".repeat(5_000);
        byte[] json = jsonReportOf("{\"name\":\"" + longName + "\"}");
        byte[] xml = xmlReport("<projectInfo><name>" + longName + "</name></projectInfo><dependencies/>");

        JsonNode first = settledDc(draft, "name.json", json);
        JsonNode second = settledDc(draft, "name.xml", xml);

        assertThat(first.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(second.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
    }

    @Test
    void aReportWithoutProjectInfoIsStillReadAndOffersNothingToConfirm() {
        Draft draft = draft();
        byte[] report = utf8("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":[]}");

        JsonNode file = settledDc(draft, "bare.json", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), file.get("id").asString());
        assertThat(metadata.get("scanDate").isNull()).isTrue();
        assertThat(metadata.get("target").isNull()).isTrue();
    }

    @Test
    void theSampleExportsCarryAProvenanceNote() {
        assertThat(new String(Samples.dependencyCheckSampleXml(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("PROVENANCE").contains("NOT the output of a real").contains("Regenerate it from a real scan");
        assertThat(new String(Samples.dependencyCheckSampleJson(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("provenance").contains("NOT the output of a real").contains("Regenerate it from a real scan");
    }
}
