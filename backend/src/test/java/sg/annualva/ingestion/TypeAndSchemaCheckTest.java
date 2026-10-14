package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 21 and 24: the type allowlist decides from the bytes (a root element, the shape of a JSON document), never from
 * the file name or declared type, applies each type's size limit, and rejects a disallowed or oversized file before
 * anything parses it; the schema check rejects a file that cannot be read to the end or lacks the structure its format
 * needs. A Dependency-Check, XCCDF or ARF file that passes is parsed (PARSED); the other formats wait at SCHEMA_OK for the parsers
 * of the next two tickets.
 */
class TypeAndSchemaCheckTest extends IngestionSeam1 {

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private void assertAccepted(JsonNode file, String format) {
        assertThat(file.get("status").asString()).as(file.toString())
                .isEqualTo(format.equals("NESSUS") || format.equals("BURP_XML") ? "SCHEMA_OK" : "PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo(format);
    }

    private void assertRejected(JsonNode file, String code) {
        assertThat(file.get("status").asString()).as(file.toString()).isEqualTo("REJECTED_" + code);
        assertThat(file.get("rejectCode").asString()).isEqualTo(code);
        assertThat(file.get("message").asString()).endsWith("so the upload was rejected.");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("a rejected file keeps no bytes").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("a rejected file keeps no bytes").isZero();
    }

    @Test
    void everyAllowedFormatIsAcceptedForItsOwnScanReportWhateverItIsCalled() {
        Draft draft = draft();

        assertAccepted(settled(draft, DEPENDENCY_CHECK, "report.txt", Samples.dependencyCheckXml()),
                "DEPENDENCY_CHECK_XML");
        assertAccepted(settled(draft, DEPENDENCY_CHECK, "report.exe", Samples.dependencyCheckJson()),
                "DEPENDENCY_CHECK_JSON");
        assertAccepted(settled(draft, SECURE_CONFIGURATION, "results.xml", Samples.xccdfResults()), "XCCDF_RESULTS");
        assertAccepted(settled(draft, SECURE_CONFIGURATION, "arf.xml", Samples.arf()), "ARF");
        assertAccepted(settled(draft, SECURE_CONFIGURATION, "scan.nessus", Samples.nessus()), "NESSUS");
        assertAccepted(settled(draft, BURP_SUITE, "issues.xml", Samples.burp()), "BURP_XML");
    }

    @Test
    void aTestResultRootIsXccdfToo() {
        Draft draft = draft();
        byte[] testResult = text("<TestResult xmlns=\"http://checklists.nist.gov/xccdf/1.2\" id=\"r1\"/>");

        assertAccepted(settled(draft, SECURE_CONFIGURATION, "result.xml", testResult), "XCCDF_RESULTS");
    }

    @Test
    void aByteOrderMarkAndLeadingWhitespaceDoNotHideTheFormat() {
        Draft draft = draft();
        byte[] json = Samples.dependencyCheckJson();
        byte[] withBom = new byte[json.length + 4];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        withBom[3] = '\n';
        System.arraycopy(json, 0, withBom, 4, json.length);

        assertAccepted(settled(draft, DEPENDENCY_CHECK, "bom.json", withBom), "DEPENDENCY_CHECK_JSON");
    }

    @ParameterizedTest(name = "{0} sent as {1} is the wrong type")
    @CsvSource({
        "burp, DEPENDENCY_CHECK",
        "nessus, DEPENDENCY_CHECK",
        "dc-xml, SECURE_CONFIGURATION",
        "dc-json, BURP_SUITE",
        "arf, BURP_SUITE",
        "html, SECURE_CONFIGURATION",
        "png, BURP_SUITE"
    })
    void aFileOfAnotherTypeIsRejectedForTheScanReportItWasUploadedTo(String sample, String reportType) {
        Draft draft = draft();
        byte[] content = switch (sample) {
            case "burp" -> Samples.burp();
            case "nessus" -> Samples.nessus();
            case "dc-xml" -> Samples.dependencyCheckXml();
            case "dc-json" -> Samples.dependencyCheckJson();
            case "arf" -> Samples.arf();
            case "html" -> Samples.html();
            default -> Samples.png();
        };

        assertRejected(settled(draft, reportType, "report.xml", content), "TYPE");
    }

    @Test
    void aJsonDocumentWithoutTheReportKeysAndAnEmptyishFileAreNotAnyType() {
        Draft draft = draft();

        assertRejected(settled(draft, DEPENDENCY_CHECK, "a.json", text("{\"hello\":\"world\"}")), "TYPE");
        assertRejected(settled(draft, DEPENDENCY_CHECK, "b.json", text("[1,2,3]")), "TYPE");
        assertRejected(settled(draft, DEPENDENCY_CHECK, "c.xml", text("   \n  ")), "TYPE");
        assertRejected(settled(draft, DEPENDENCY_CHECK, "d.xml", text("<<<not xml")), "TYPE");
        assertRejected(settled(draft, DEPENDENCY_CHECK, "e.xml", text("<?xml version=\"1.0\"?>")), "TYPE");
    }

    @Test
    void evidenceHasNoAllowedTypeUntilItsOwnTicket() {
        Draft draft = draft();
        var response = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "EVIDENCE", "report.pdf",
                text("%PDF-1.7 evidence"));
        assertThat(response.status()).isEqualTo(202);
        pass();
        pass();

        JsonNode file = status(draft.smo(), draft.submissionId(),
                sg.annualva.harness.ApiClient.json(response).get("id").asString());

        assertRejected(file, "TYPE");
    }

    @Test
    void aFileOverTheLimitForItsTypeIsRejectedBeforeAnythingReadsIt() {
        Draft draft = draft();
        byte[] big = Samples.dependencyCheckXmlOfSize(25 * 1024 * 1024 + 1024);

        assertRejected(settled(draft, DEPENDENCY_CHECK, "big.xml", big), "TOO_LARGE");
    }

    @Test
    void aFileJustUnderTheLimitIsAccepted() {
        Draft draft = draft();
        byte[] large = Samples.dependencyCheckXmlOfSize(25 * 1024 * 1024 - 1024);

        assertAccepted(settled(draft, DEPENDENCY_CHECK, "large.xml", large), "DEPENDENCY_CHECK_XML");
    }

    @Test
    void aTruncatedXmlDocumentIsUnreadable() {
        Draft draft = draft();
        byte[] truncated = text("<analysis xmlns=\"https://example.invalid/dc\"><scanInfo>");

        assertRejected(settled(draft, DEPENDENCY_CHECK, "cut.xml", truncated), "UNREADABLE");
    }

    @Test
    void aTruncatedJsonDocumentIsUnreadableAndATrailingValueToo() {
        Draft draft = draft();
        byte[] cut = text("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":[{\"fileName\":");
        byte[] trailing = text("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":[]} {}");

        assertRejected(settled(draft, DEPENDENCY_CHECK, "cut.json", cut), "UNREADABLE");
        assertRejected(settled(draft, DEPENDENCY_CHECK, "two.json", trailing), "UNREADABLE");
    }

    @Test
    void aJsonReportWhoseDependenciesAreNotAListFailsTheSchemaCheck() {
        Draft draft = draft();
        byte[] notAList = text("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":{\"a\":1}}");

        assertRejected(settled(draft, DEPENDENCY_CHECK, "shape.json", notAList), "SCHEMA");
    }

    @Test
    void aJsonDocumentNestedFarDeeperThanTheReaderAllowsIsUnreadable() {
        Draft draft = draft();
        String deep = "{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":" + "[".repeat(1000) + "]".repeat(1000) + "}";

        assertRejected(settled(draft, DEPENDENCY_CHECK, "deep.json", text(deep)), "UNREADABLE");
    }

    @Test
    void aDocumentNestedFarDeeperThanAnyReportFailsTheSchemaCheck() {
        Draft draft = draft();
        String deep = "<analysis>" + "<a>".repeat(150) + "</a>".repeat(150) + "</analysis>";

        assertRejected(settled(draft, DEPENDENCY_CHECK, "deep.xml", text(deep)), "SCHEMA");
    }

    @Test
    void anEntityBombIsNeverExpandedAndTheFileIsRejectedQuickly() {
        Draft draft = draft();
        String bomb = """
                <?xml version="1.0"?>
                <!DOCTYPE analysis [
                <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
                <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
                <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
                ]>
                <analysis><scanInfo>&d;&d;&d;&d;&d;&d;&d;&d;</scanInfo></analysis>
                """;

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "bomb.xml", text(bomb));

        assertRejected(file, "UNREADABLE");
    }

    @Test
    void anExternalEntityIsNeverLoaded() {
        Draft draft = draft();
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE analysis [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
                <analysis><scanInfo>&secret;</scanInfo></analysis>
                """;

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "xxe.xml", text(xxe));

        assertRejected(file, "UNREADABLE");
    }
}
