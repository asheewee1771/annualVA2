package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 20a, 25 and 28: an OWASP Dependency-Check XML or JSON export uploaded as the Dependency-Check Scan Report is read
 * into Findings, one per CVE or GHSA and package within the System, with one occurrence per dependency; the scan date and
 * the project name are read for the SMO/Tech Lead to confirm. The two formats of the sample export describe the same
 * Findings and must read to the same result.
 */
class DependencyCheckParseTest extends DependencyCheckSeam1 {

    @Test
    void anXmlExportIsReadIntoFindingsKeyedByTheCveAndThePackage() {
        Draft draft = draft();

        JsonNode file = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("DEPENDENCY_CHECK_XML");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK",
                "PARSED");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).isEqualTo(7);
        assertThat(summary(page)).containsExactlyEntriesOf(sampleFindings());
    }

    @Test
    void aJsonExportIsReadToTheSameFindingsAsTheXmlOne() {
        Draft draft = draft();

        JsonNode file = settledDc(draft, "dc.json", Samples.dependencyCheckSampleJson());

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("DEPENDENCY_CHECK_JSON");
        assertThat(summary(findings(draft.smo(), draft.submissionId()))).containsExactlyEntriesOf(sampleFindings());
    }

    @Test
    void eachFindingCarriesItsReportTypeIdentifierPackageSeveritySourceFileAndOccurrenceCountScopedToTheSystem() {
        Draft draft = draft();
        String id = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(page.get("page").asInt()).isZero();
        JsonNode text = page.get("items").get(2);
        assertThat(text.get("reportType").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(text.get("identifier").asString()).isEqualTo("CVE-2022-42889");
        assertThat(text.get("packageOrRule").asString()).as("the package URL").isEqualTo(PURL_TEXT);
        assertThat(text.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(text.get("occurrenceCount").asInt()).as("one per dependency: the jar is in two places").isEqualTo(2);
        assertThat(text.get("sourceFiles")).hasSize(1);
        assertThat(text.get("sourceFiles").get(0).get("id").asString()).isEqualTo(id);
        assertThat(text.get("sourceFiles").get(0).get("name").asString()).isEqualTo("dc.xml");
        JsonNode widget = page.get("items").get(3);
        assertThat(widget.get("packageOrRule").asString()).as("no package URL, so the file name")
                .isEqualTo("legacy-widget.dll");
        for (JsonNode finding : page.get("items")) {
            assertThat(finding.get("systemId").asString()).isEqualTo(draft.systemId());
        }
    }

    @Test
    void aVulnerabilityNamedByTwoSourcesForOneDependencyIsOneOccurrenceAndASuppressedOrRelatedOneIsNoFinding() {
        Draft draft = draft();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(summary(page)).containsEntry("dc|CVE-2021-44228|" + PURL_LOG4J, "CRITICAL/1");
        assertThat(page.toString()).doesNotContain("CVE-2000-0001").doesNotContain("clean-helper");
    }

    @Test
    void theScanDateAndTheProjectNameArePreFilledForTheSmoToConfirm() {
        Draft draft = draft();
        String xml = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();
        String json = settledDc(draft, "dc.json", Samples.dependencyCheckSampleJson()).get("id").asString();

        for (String id : List.of(xml, json)) {
            JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);
            assertThat(metadata.get("fileId").asString()).isEqualTo(id);
            assertThat(metadata.get("target").asString()).isEqualTo("throwaway-project");
            assertThat(metadata.get("scanDate").asString()).as("10:00 at +08:00, in UTC")
                    .startsWith("2027-07-20T02:00:00");
        }
    }

    @Test
    void theStatusAndTheFileListCarryNothingReadFromTheReport() {
        Draft draft = draft();
        String id = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();

        String status = draft.smo().get(fileAt(draft.submissionId(), id)).body();
        String list = draft.smo().get(filesOf(draft.submissionId())).body();

        assertThat(status).doesNotContain("throwaway-project").doesNotContain("scanDate").doesNotContain("target");
        assertThat(list).doesNotContain("throwaway-project").doesNotContain("CVE-");
    }

    @Test
    void aFileThatIsNotParsedYetOrWasRejectedHasNoMetadata() {
        Draft draft = draft();
        String rejected = settledDc(draft, "page.html", Samples.html()).get("id").asString();
        String waiting = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "later.xml",
                Samples.dependencyCheckSampleXml()).get("id").asString();

        assertThat(draft.smo().get(fileAt(draft.submissionId(), rejected) + "/metadata").status()).isEqualTo(404);
        assertThat(draft.smo().get(fileAt(draft.submissionId(), waiting) + "/metadata").status()).isEqualTo(404);
    }

    @Test
    void aFormatWithNoParserYetWaitsAtSchemaOkWithNoFindingsAndNothingToPreFill() {
        Draft draft = draft();

        JsonNode file = settled(draft, SECURE_CONFIGURATION, "scan.nessus", Samples.nessus());

        assertThat(file.get("status").asString()).isEqualTo("SCHEMA_OK");
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), file.get("id").asString());
        assertThat(metadata.get("scanDate").isNull()).isTrue();
        assertThat(metadata.get("target").isNull()).isTrue();
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
    }

    @Test
    void severalFilesOfOneScanReportMakeOneFindingPerKeyWithAnOccurrenceForEachPlaceAndTheWorstSeverity() {
        Draft draft = draft();
        settledDc(draft, "first.xml", Samples.dependencyCheckSampleXml());
        byte[] second = jsonReport(
                jsonDependency("legacy-widget.dll", "/srv/other/legacy-widget.dll", null,
                        jsonVulnerability("CVE-2019-0001", v3("9.8")), jsonVulnerability("CVE-2030-0001", v3("5.0"))),
                jsonDependency("log4j-core-2.14.1.jar", "/srv/other/log4j-core-2.14.1.jar", PURL_LOG4J,
                        jsonVulnerability("CVE-2021-44228", v3("10.0"))));
        settledDc(draft, "second.json", second);

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(page.get("total").asInt()).as("seven, and one new").isEqualTo(8);
        JsonNode widget = byIdentifier(page, "CVE-2019-0001");
        assertThat(widget.get("severity").asString()).as("MEDIUM in one file, CRITICAL in the other")
                .isEqualTo("CRITICAL");
        assertThat(widget.get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(sourceNames(widget)).containsExactly("first.xml", "second.json");
        JsonNode log4j = byIdentifier(page, "CVE-2021-44228");
        assertThat(log4j.get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(sourceNames(log4j)).as("in the order the files were uploaded")
                .containsExactly("first.xml", "second.json");
        assertThat(sourceNames(byIdentifier(page, "CVE-2030-0001"))).containsExactly("second.json");
        assertThat(sourceNames(byIdentifier(page, "CVE-2020-9488"))).containsExactly("first.xml");
    }

    @Test
    void theSameCveAndPackageInAnotherSystemIsAFindingOfItsOwnAndNeitherChangesTheOther() {
        Draft draft = draft();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        ApiClient other = signedInAs(SMO_B);
        String otherSystem = idOf("SYS-F2");
        Draft second = new Draft(other, otherSystem, started(other, otherSystem).get("id").asString());
        byte[] lessSevere = jsonReport(jsonDependency("log4j-core-2.14.1.jar", "/elsewhere/log4j-core-2.14.1.jar",
                PURL_LOG4J, jsonVulnerability("CVE-2021-44228", v3("1.0"))));

        settledDc(second, "dc.json", lessSevere);

        JsonNode mine = findings(draft.smo(), draft.submissionId());
        JsonNode theirs = findings(other, second.submissionId());
        assertThat(summary(mine)).as("this System's Finding keeps its severity and its one place")
                .containsExactlyEntriesOf(sampleFindings());
        assertThat(summary(theirs)).containsExactly(Map.entry("dc|CVE-2021-44228|" + PURL_LOG4J, "LOW/1"));
        JsonNode theirFinding = theirs.get("items").get(0);
        assertThat(theirFinding.get("systemId").asString()).isEqualTo(otherSystem);
        assertThat(mine.get("items").get(0).get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(theirFinding.get("id").asString()).isNotEqualTo(mine.get("items").get(0).get("id").asString());
    }

    @Test
    void aReportOfThousandsOfFindingsIsKeptAndListedInPages() {
        Draft draft = draft();
        String[] dependencies = new String[3000];
        for (int n = 0; n < dependencies.length; n++) {
            dependencies[n] = jsonDependency("lib-" + n + ".jar", "/opt/app/lib-" + n + ".jar",
                    "pkg:maven/example/lib-" + n + "@1.0", jsonVulnerability("CVE-2027-" + (10000 + n), v3("5.0")));
        }

        JsonNode file = settledDc(draft, "big.json", jsonReport(dependencies));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        JsonNode page = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?page=2&size=500"));
        assertThat(page.get("total").asInt()).isEqualTo(3000);
        assertThat(page.get("page").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(500);
    }

    private static JsonNode byIdentifier(JsonNode page, String identifier) {
        for (JsonNode item : page.get("items")) {
            if (item.get("identifier").asString().equals(identifier)) {
                return item;
            }
        }
        throw new AssertionError("no Finding " + identifier);
    }

    private static List<String> sourceNames(JsonNode finding) {
        List<String> names = new ArrayList<>();
        finding.get("sourceFiles").forEach(source -> names.add(source.get("name").asString()));
        return names;
    }
}
