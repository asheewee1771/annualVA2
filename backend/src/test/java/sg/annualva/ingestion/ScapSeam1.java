package sg.annualva.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What the Secure Configuration (SCAP) parser tests share: a CIS-CAT or OpenSCAP results file is uploaded as the Secure
 * Configuration Scan Report and moved through the pipeline, then its Findings and pre-filled metadata are read back through the
 * HTTP API. Small reports are written inline so that a test shows exactly what it feeds the parser; the two sample exports are
 * in {@code samples/scap}.
 */
abstract class ScapSeam1 extends ParserSeam1 {

    static final String XCCDF_12 = "http://checklists.nist.gov/xccdf/1.2";
    static final String XCCDF_11 = "http://checklists.nist.gov/xccdf/1.1";
    static final String ARF_NAMESPACE = "http://scap.nist.gov/schema/asset-reporting-format/1.1";

    static final String CIS_RULE = "xccdf_org.cisecurity.benchmarks_rule_";
    static final String SSG_RULE = "xccdf_org.ssgproject.content_rule_";

    /** Uploads a Secure Configuration file and runs the job until it has its verdict. */
    protected JsonNode settledScap(Draft draft, String name, byte[] content) {
        return settled(draft, SECURE_CONFIGURATION, name, content);
    }

    /** identity key to "SEVERITY/places", and "/INCOMPLETE" when the Finding is a check that did not complete. */
    protected static Map<String, String> scapSummary(JsonNode page) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (JsonNode item : page.get("items")) {
            summary.put(item.get("identityKey").asString(), item.get("severity").asString() + "/"
                    + item.get("occurrenceCount").asInt() + (item.get("didNotComplete").asBoolean() ? "/INCOMPLETE" : ""));
        }
        return summary;
    }

    /** What the CIS-CAT sample holds, in the order the list gives it (every severity is unrated, so MEDIUM). */
    protected static Map<String, String> cisCatFindings() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("scap|" + CIS_RULE + "1.1.2_Ensure_tmp_is_a_separate_partition", "MEDIUM/1");
        expected.put("scap|" + CIS_RULE + "1.1.3_Ensure_nodev_option_set_on_tmp_partition", "MEDIUM/1");
        expected.put("scap|" + CIS_RULE + "2.1.1_Ensure_time_synchronization_is_in_use", "MEDIUM/1/INCOMPLETE");
        expected.put("scap|" + CIS_RULE + "2.1.2_Ensure_chrony_is_configured", "MEDIUM/1/INCOMPLETE");
        return expected;
    }

    /** What the OpenSCAP sample holds (two hosts), in the order the list gives it. */
    protected static Map<String, String> openScapFindings() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("scap|" + SSG_RULE + "sshd_disable_root_login", "HIGH/2");
        expected.put("scap|" + SSG_RULE + "accounts_tmout", "MEDIUM/1");
        expected.put("scap|" + SSG_RULE + "audit_rules_time_adjtimex", "MEDIUM/2/INCOMPLETE");
        expected.put("scap|" + SSG_RULE + "package_aide_installed", "MEDIUM/2");
        expected.put("scap|" + SSG_RULE + "accounts_password_minlen", "LOW/1/INCOMPLETE");
        expected.put("scap|" + SSG_RULE + "file_permissions_etc_shadow", "LOW/1");
        return expected;
    }

    // ---- small reports, written inline ----

    /** One rule result; a null severity writes none. */
    protected static String ruleResult(String idref, String severity, String result) {
        return "<rule-result idref=\"" + idref + "\"" + (severity == null ? "" : " severity=\"" + severity + "\"")
                + "><result>" + result + "</result></rule-result>";
    }

    /**
     * A TestResult (in the XCCDF namespace of its parent) of this host and end time around these rule results; a null target
     * or end time writes none.
     */
    protected static String testResult(String target, String endTime, String... ruleResults) {
        return "<TestResult id=\"xccdf_testresult\"" + (endTime == null ? "" : " end-time=\"" + endTime + "\"") + ">"
                + (target == null ? "" : "<target>" + target + "</target>") + String.join("", ruleResults)
                + "</TestResult>";
    }

    /** An XCCDF results file: a Benchmark in this namespace holding these TestResults. */
    protected static byte[] xccdfIn(String namespace, String... testResults) {
        return utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Benchmark xmlns=\"" + namespace
                + "\" id=\"xccdf_benchmark\">" + String.join("", testResults) + "</Benchmark>");
    }

    protected static byte[] xccdf(String... testResults) {
        return xccdfIn(XCCDF_12, testResults);
    }

    /** An ARF file: one report per TestResult, the XCCDF namespace declared on each. */
    protected static byte[] arf(String... testResults) {
        StringBuilder reports = new StringBuilder();
        for (String testResult : testResults) {
            reports.append("<arf:report id=\"r\"><arf:content>")
                    .append(testResult.replaceFirst("<TestResult ", "<TestResult xmlns=\"" + XCCDF_12 + "\" "))
                    .append("</arf:content></arf:report>");
        }
        return utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><arf:asset-report-collection xmlns:arf=\"" + ARF_NAMESPACE
                + "\"><arf:reports>" + reports + "</arf:reports></arf:asset-report-collection>");
    }
}
