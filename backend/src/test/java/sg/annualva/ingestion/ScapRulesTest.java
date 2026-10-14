package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 21 and 25 for the SCAP parser: a file that is not an ARF or an XCCDF results file is rejected as REJECTED_SCHEMA
 * with nothing kept and nothing said back about what it held; the results are only ever read where the formats put them (a
 * decoy in a report request is no result); text read from the file is cleaned; the date is read in every form the tools
 * write; and no XML reader obeys a DTD or an entity.
 */
class ScapRulesTest extends ScapSeam1 {

    private static final String SENTINEL = "SCAP-SENTINEL-9917";

    private static final String RESULT_OK = "<rule-result idref=\"r.ok\"><result>fail</result></rule-result>";

    @Test
    void aFileThatIsNotAnArfOrAnXccdfResultsFileIsRejectedAsSchemaAndNothingIsKept() {
        Draft draft = draft();
        Map<String, byte[]> hostile = new LinkedHashMap<>();
        hostile.put("benchmark-without-results.xml",
                utf8("<Benchmark xmlns=\"" + XCCDF_12 + "\" id=\"b\"><Rule id=\"r\"/></Benchmark>"));
        hostile.put("arf-without-results.xml", utf8("<arf:asset-report-collection xmlns:arf=\"" + ARF_NAMESPACE
                + "\"><arf:reports/></arf:asset-report-collection>"));
        hostile.put("arf-wrong-namespace.xml", utf8("<arf:asset-report-collection xmlns:arf=\"http://example.invalid/arf\">"
                + "<arf:reports><arf:report><arf:content>" + testResultIn(XCCDF_12, "h.invalid", RESULT_OK)
                + "</arf:content></arf:report></arf:reports></arf:asset-report-collection>"));
        hostile.put("xccdf-wrong-namespace.xml",
                xccdfIn("http://example.invalid/xccdf", testResult("h.invalid", null, RESULT_OK)));
        hostile.put("xccdf-no-namespace.xml",
                utf8("<Benchmark id=\"b\">" + testResult("h.invalid", null, RESULT_OK) + "</Benchmark>"));
        hostile.put("test-result-in-another-namespace.xml", xccdf("<TestResult xmlns=\"http://example.invalid/x\" id=\"t\">"
                + "<target>h.invalid</target>" + RESULT_OK + "</TestResult>"));
        hostile.put("test-result-in-the-wrong-place.xml", utf8("<arf:asset-report-collection xmlns:arf=\"" + ARF_NAMESPACE
                + "\"><arf:reports>" + testResultIn(XCCDF_12, "h.invalid", RESULT_OK)
                + "</arf:reports></arf:asset-report-collection>"));
        hostile.put("test-result-deep-in-a-benchmark.xml", xccdf("<Group id=\"g\">"
                + testResult("h.invalid", null, RESULT_OK) + "</Group>"));
        hostile.put("no-idref.xml", xccdf(testResult("h.invalid", null,
                "<rule-result><result>fail</result></rule-result>")));
        hostile.put("empty-idref.xml", xccdf(testResult("h.invalid", null, ruleResult("", null, "fail"))));
        hostile.put("long-idref.xml", xccdf(testResult("h.invalid", null, ruleResult("r".repeat(501), null, "fail"))));
        hostile.put("long-host.xml", xccdf(testResult("h".repeat(501), null, RESULT_OK)));
        hostile.put("markup-in-a-result.xml", xccdf(testResult("h.invalid", null,
                "<rule-result idref=\"r\"><result><b>fail</b></result></rule-result>")));
        hostile.put("markup-in-a-target.xml", xccdf(testResult("<b>h</b>.invalid", null, RESULT_OK)));
        hostile.put("huge-text-in-a-target.xml", xccdf(testResult("h".repeat(5000), null, RESULT_OK)));
        hostile.put("huge-text-in-an-address-nobody-uses.xml", xccdf("<TestResult id=\"t\"><target>h.invalid</target>"
                + "<target-address>" + "9".repeat(5000) + "</target-address>" + RESULT_OK + "</TestResult>"));

        for (var entry : hostile.entrySet()) {
            JsonNode file = settledScap(draft, entry.getKey(), entry.getValue());

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

    private static String testResultIn(String namespace, String host, String... results) {
        return "<TestResult xmlns=\"" + namespace + "\" id=\"t\"><target>" + host + "</target>" + String.join("", results)
                + "</TestResult>";
    }

    @Test
    void aRejectionNeverEchoesWhatTheFileSaid() {
        Draft draft = draft();
        byte[] report = xccdf(testResult(SENTINEL + ".invalid", null, ruleResult(SENTINEL + "x".repeat(500), null, "fail")));

        JsonNode file = settledScap(draft, "x.xml", report);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(file.toString()).doesNotContain(SENTINEL);
        assertThat(draft.smo().get(filesOf(draft.submissionId())).body()).doesNotContain(SENTINEL);
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain(SENTINEL);
    }

    @Test
    void resultsAreReadOnlyWhereTheFormatsPutThemSoADecoyInAReportRequestOrAGroupIsNoFinding() {
        Draft draft = draft();
        String decoy = "<TestResult xmlns=\"" + XCCDF_12 + "\" id=\"decoy\"><target>decoy.invalid</target>"
                + "<rule-result idref=\"r.decoy\"><result>fail</result></rule-result></TestResult>";
        byte[] report = utf8("<arf:asset-report-collection xmlns:arf=\"" + ARF_NAMESPACE + "\">"
                + "<arf:report-requests><arf:report-request id=\"q\"><arf:content>" + decoy
                + "</arf:content></arf:report-request></arf:report-requests>"
                + "<arf:reports><arf:report id=\"r\"><arf:content>"
                + "<TestResult xmlns=\"" + XCCDF_12 + "\" id=\"real\"><target>real.invalid</target>"
                + "<rule-result idref=\"r.real\"><result>fail</result>"
                + "<check><check-content-ref name=\"x\"/></check>"
                + "<rule-result idref=\"r.nested\"><result>fail</result></rule-result></rule-result>"
                + "<target-facts><rule-result idref=\"r.facts\"><result>fail</result></rule-result></target-facts>"
                + "</TestResult></arf:content></arf:report></arf:reports></arf:asset-report-collection>");

        JsonNode file = settledScap(draft, "decoy.xml", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId())))
                .containsExactly(Map.entry("scap|r.real", "MEDIUM/1"));
        assertThat(metadata(draft.smo(), draft.submissionId(), file.get("id").asString()).get("target").asString())
                .isEqualTo("real.invalid");
    }

    @Test
    void textReadFromTheReportLosesControlAndDirectionCharactersAndTheTargetIsCutToAFixedLength() {
        Draft draft = draft();
        String longHost = "h".repeat(300);
        byte[] report = xccdf(
                "<TestResult id=\"t\"><target>  ho&#x202E;st&#10;a  </target>"
                        + "<rule-result idref=\"r&#x202E;.&#10;1\"><result>fail</result></rule-result></TestResult>",
                testResult(longHost, null, RESULT_OK));

        String id = settledScap(draft, "clean.xml", report).get("id").asString();

        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);
        assertThat(metadata.get("target").asString()).as("cleaned, then cut to 200").startsWith("hosta, hhhh").hasSize(200);
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId())).keySet())
                .containsExactlyInAnyOrder("scap|r.1", "scap|r.ok");
    }

    @Test
    void theScanDateIsReadInEveryFormTheToolsWriteAndIsNullWhenTheFileSaysNothingUsable() {
        Draft draft = draft();
        Map<String, String> dates = new LinkedHashMap<>();
        dates.put("2027-07-20T10:00:00Z", "2027-07-20T10:00:00");
        dates.put("2027-07-20T10:00:00+08:00", "2027-07-20T02:00:00");
        dates.put("2027-07-20T10:00:00.123456+08:00", "2027-07-20T02:00:00");
        dates.put("2027-07-20T10:00:00", "2027-07-20T10:00:00");
        dates.put("last tuesday", null);
        dates.put("1850-01-01T00:00:00Z", null);
        dates.put("+999999999-01-01T00:00:00Z", null);
        dates.put(null, null);
        for (var entry : dates.entrySet()) {
            String id = settledScap(draft, "d.xml", xccdf(testResult("h.invalid", entry.getKey(), RESULT_OK)))
                    .get("id").asString();

            JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);

            if (entry.getValue() == null) {
                assertThat(metadata.get("scanDate").isNull()).as(String.valueOf(entry.getKey())).isTrue();
            } else {
                assertThat(metadata.get("scanDate").asString()).as(entry.getKey()).startsWith(entry.getValue());
            }
            assertThat(metadata.get("target").asString()).isEqualTo("h.invalid");
        }
    }

    @Test
    void aDtdAndItsEntitiesAreNeverObeyed() {
        Draft draft = draft();
        String xxe = "<!DOCTYPE Benchmark [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>";
        String internal = "<!DOCTYPE Benchmark [<!ENTITY rule \"r.entity\">]>";
        String bomb = "<!DOCTYPE Benchmark [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]>";
        Map<String, byte[]> documents = new LinkedHashMap<>();
        documents.put("xxe.xml", utf8("<?xml version=\"1.0\"?>" + xxe + "<Benchmark xmlns=\"" + XCCDF_12
                + "\"><TestResult id=\"t\"><target>&xxe;</target>" + RESULT_OK + "</TestResult></Benchmark>"));
        documents.put("internal.xml", utf8("<?xml version=\"1.0\"?>" + internal + "<Benchmark xmlns=\"" + XCCDF_12
                + "\"><TestResult id=\"t\"><target>h.invalid</target><rule-result idref=\"&rule;\">"
                + "<result>fail</result></rule-result></TestResult></Benchmark>"));
        documents.put("bomb.xml", utf8("<?xml version=\"1.0\"?>" + bomb + "<Benchmark xmlns=\"" + XCCDF_12
                + "\"><TestResult id=\"t\"><target>&c;</target>" + RESULT_OK + "</TestResult></Benchmark>"));

        for (var entry : documents.entrySet()) {
            JsonNode file = settledScap(draft, entry.getKey(), entry.getValue());

            assertThat(file.get("status").asString()).as(entry.getKey()).startsWith("REJECTED_");
        }
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
        assertThat(findings(draft.smo(), draft.submissionId()).toString()).doesNotContain("r.entity");
    }

    @Test
    void aDoctypeThatDeclaresNothingIsSkippedNotObeyed() {
        Draft draft = draft();
        byte[] report = utf8("<?xml version=\"1.0\"?><!DOCTYPE Benchmark><Benchmark xmlns=\"" + XCCDF_12 + "\">"
                + testResult("h.invalid", null, RESULT_OK) + "</Benchmark>");

        JsonNode file = settledScap(draft, "doctype.xml", report);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(Map.entry("scap|r.ok",
                "MEDIUM/1"));
    }

    @Test
    void theSampleExportsCarryAProvenanceNote() {
        for (byte[] sample : new byte[][] {Samples.cisCatXccdfSample(), Samples.openScapArfSample()}) {
            assertThat(new String(sample, StandardCharsets.UTF_8)).contains("PROVENANCE").contains("NOT the output of a real")
                    .contains("Regenerate it from a real scan");
        }
    }
}
