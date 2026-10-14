package sg.annualva.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What the Dependency-Check parser tests share: a report is uploaded and moved through the pipeline, then its Findings
 * and its pre-filled metadata are read back through the HTTP API, exactly as a person's browser would.
 */
abstract class DependencyCheckSeam1 extends ParserSeam1 {

    static final String PURL_LOG4J = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    static final String PURL_TEXT = "pkg:maven/org.apache.commons/commons-text@1.9";

    /** Uploads a Dependency-Check file and runs the job until it has its verdict. */
    protected JsonNode settledDc(Draft draft, String name, byte[] content) {
        return settled(draft, DEPENDENCY_CHECK, name, content);
    }

    /** What the sample export holds, in the order the list gives it: the most severe first, then by identifier. */
    protected static Map<String, String> sampleFindings() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("dc|CVE-2021-44228|" + PURL_LOG4J, "CRITICAL/1");
        expected.put("dc|CVE-2021-45046|" + PURL_LOG4J, "CRITICAL/1");
        expected.put("dc|CVE-2022-42889|" + PURL_TEXT, "CRITICAL/2");
        expected.put("dc|GHSA-aaaa-bbbb-cccc|legacy-widget.dll", "HIGH/1");
        expected.put("dc|CVE-2018-0002|legacy-widget.dll", "MEDIUM/1");
        expected.put("dc|CVE-2019-0001|legacy-widget.dll", "MEDIUM/1");
        expected.put("dc|CVE-2020-9488|" + PURL_LOG4J, "LOW/1");
        return expected;
    }

    // ---- small Dependency-Check reports, written inline so a test shows exactly what it feeds the parser ----

    /** A vulnerability of a JSON report: its name and the raw JSON of its ratings (may be empty). */
    protected static String jsonVulnerability(String name, String ratings) {
        return "{\"source\":\"NVD\",\"name\":\"" + name + "\"" + (ratings.isEmpty() ? "" : "," + ratings) + "}";
    }

    protected static String v3(String score) {
        return "\"cvssv3\":{\"baseScore\":" + score + "}";
    }

    protected static String v2(String score) {
        return "\"cvssv2\":{\"score\":" + score + "}";
    }

    protected static String jsonDependency(String fileName, String path, String purl, String... vulnerabilities) {
        return "{\"fileName\":\"" + fileName + "\",\"filePath\":\"" + path + "\""
                + (purl == null ? "" : ",\"packages\":[{\"id\":\"" + purl + "\"}]")
                + ",\"vulnerabilities\":[" + String.join(",", vulnerabilities) + "]}";
    }

    protected static byte[] jsonReportOf(String projectInfo, String... dependencies) {
        return utf8("{\"reportSchema\":\"1.1\",\"scanInfo\":{\"engineVersion\":\"9.0.9\"},\"projectInfo\":" + projectInfo
                + ",\"dependencies\":[" + String.join(",", dependencies) + "]}");
    }

    protected static byte[] jsonReport(String... dependencies) {
        return jsonReportOf("{\"name\":\"throwaway-project\",\"reportDate\":\"2027-07-20T10:00:00Z\"}", dependencies);
    }

    protected static final String DC_NAMESPACE = "https://jeremylong.github.io/DependencyCheck/dependency-check.2.5.xsd";

    /** An XML report in the DependencyCheck namespace around this body. */
    protected static byte[] xmlReport(String body) {
        return utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><analysis xmlns=\"" + DC_NAMESPACE + "\">" + body
                + "</analysis>");
    }
}
