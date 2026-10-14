package sg.annualva.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What the Secure Configuration (Nessus) parser tests share: a {@code .nessus} compliance audit is uploaded as the Secure
 * Configuration Scan Report and moved through the pipeline, then its Findings and pre-filled metadata are read back through the
 * HTTP API. Small reports are written inline so that a test shows exactly what it feeds the parser; the sample export is in
 * {@code samples/nessus}.
 */
abstract class NessusSeam1 extends ParserSeam1 {

    /** The check ids of the sample export, in the order of its checks. */
    static final String CRAMFS = "a1b2c3d4e5f60718293a4b5c6d7e0001";
    static final String TMP_PARTITION = "a1b2c3d4e5f60718293a4b5c6d7e0002";
    static final String TMP_NODEV = "a1b2c3d4e5f60718293a4b5c6d7e0003";
    static final String SSH_ACCESS = "a1b2c3d4e5f60718293a4b5c6d7e0004";
    static final String PASSWD = "a1b2c3d4e5f60718293a4b5c6d7e0005";
    static final String IP_FORWARDING = "a1b2c3d4e5f60718293a4b5c6d7e0006";

    /** Uploads a Secure Configuration file and runs the job until it has its verdict. */
    protected JsonNode settledNessus(Draft draft, String name, byte[] content) {
        return settled(draft, SECURE_CONFIGURATION, name, content);
    }

    /** What the sample export holds, in the order the list gives it (worst first, then by name). */
    protected static Map<String, String> sampleFindings() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("nessus|" + TMP_PARTITION, "HIGH/2");
        expected.put("nessus|" + TMP_NODEV, "HIGH/1");
        expected.put("nessus|" + SSH_ACCESS, "HIGH/2");
        expected.put("nessus|" + IP_FORWARDING, "MEDIUM/1/INCOMPLETE");
        expected.put("nessus|" + PASSWD, "LOW/2/INCOMPLETE");
        return expected;
    }

    // ---- small reports, written inline ----

    /**
     * A compliance check result as Nessus writes it: a null id, name, result or severity writes none. The long plugin output and
     * description are what makes up most of a real export, and what the parser must not hold.
     */
    protected static String check(String id, String name, String result, String severity) {
        return "<ReportItem port=\"0\" svc_name=\"general\" protocol=\"tcp\"" + (severity == null ? "" : " severity=\"" + severity + "\"")
                + " pluginID=\"21157\" pluginName=\"Unix Compliance Checks\" pluginFamily=\"Policy Compliance\">"
                + "<description>Invented description.</description><plugin_output>Invented output.</plugin_output>"
                + (id == null ? "" : "<cm:compliance-check-id>" + id + "</cm:compliance-check-id>")
                + (name == null ? "" : "<cm:compliance-check-name>" + name + "</cm:compliance-check-name>")
                + (result == null ? "" : "<cm:compliance-result>" + result + "</cm:compliance-result>") + "</ReportItem>";
    }

    /** A result that is not a compliance check: a vulnerability or an information plugin. */
    protected static String notACheck(String severity) {
        return "<ReportItem port=\"443\" svc_name=\"www\" protocol=\"tcp\" severity=\"" + severity
                + "\" pluginID=\"12345\" pluginName=\"Invented\" pluginFamily=\"Web Servers\"><description>Invented.</description>"
                + "<plugin_output>Invented output.</plugin_output></ReportItem>";
    }

    /** One host with these results; a null name or end time writes none. */
    protected static String host(String name, String hostEnd, String... items) {
        return "<ReportHost" + (name == null ? "" : " name=\"" + name + "\"") + "><HostProperties>"
                + (hostEnd == null ? "" : "<tag name=\"HOST_END\">" + hostEnd + "</tag>")
                + "<tag name=\"host-ip\">192.0.2.1</tag></HostProperties>" + String.join("", items) + "</ReportHost>";
    }

    /** A {@code .nessus} export of these hosts. */
    protected static byte[] nessus(String... hosts) {
        return utf8("<?xml version=\"1.0\" ?><NessusClientData_v2><Policy><policyName>invented</policyName></Policy>"
                + "<Report name=\"invented\" xmlns:cm=\"http://www.nessus.org/cm\">" + String.join("", hosts)
                + "</Report></NessusClientData_v2>");
    }
}
