package sg.annualva.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What the Burp Suite parser tests share: a Burp XML issue export is uploaded as the Burp Suite Scan Report and moved through
 * the pipeline, then its Findings and pre-filled metadata are read back through the HTTP API. Small exports are written inline
 * so that a test shows exactly what it feeds the parser; the sample export is in {@code samples/burp}.
 */
abstract class BurpSeam1 extends ParserSeam1 {

    static final String XSS = "2097920";
    static final String SQL_INJECTION = "1049088";
    static final String COOKIE = "5244416";
    static final String EMAIL = "16777472";

    static final String APP = "https://throwaway-app.invalid";
    static final String API = "https://api.throwaway-app.invalid";

    /** The identity keys of the sample export's Findings. */
    static final String XSS_KEY = "burp|" + XSS + "|" + APP + "|/search";
    static final String SQL_KEY = "burp|" + SQL_INJECTION + "|" + API + "|/v1/items";
    static final String COOKIE_KEY = "burp|" + COOKIE + "|" + APP + "|/";
    static final String EMAIL_KEY = "burp|" + EMAIL + "|" + APP + "|/contact";

    /** Uploads a Burp Suite file and runs the job until it has its verdict. */
    protected JsonNode settledBurp(Draft draft, String name, byte[] content) {
        return settled(draft, BURP_SUITE, name, content);
    }

    /** What the sample export holds, in the order the list gives it, as "SEVERITY/places/CONFIDENCE". */
    protected static Map<String, String> sampleFindings() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(XSS_KEY, "HIGH/2/CERTAIN");
        expected.put(SQL_KEY, "HIGH/1/TENTATIVE");
        expected.put(COOKIE_KEY, "LOW/1/FIRM");
        expected.put(EMAIL_KEY, "INFORMATIONAL/1/CERTAIN");
        return expected;
    }

    /** identity key to "SEVERITY/places/CONFIDENCE" (NONE for a Finding with no confidence), in the order the list gave them. */
    protected static Map<String, String> burpSummary(JsonNode page) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (JsonNode item : page.get("items")) {
            JsonNode confidence = item.get("confidence");
            summary.put(item.get("identityKey").asString(), item.get("severity").asString() + "/"
                    + item.get("occurrenceCount").asInt() + "/" + (confidence.isNull() ? "NONE" : confidence.asString()));
        }
        return summary;
    }

    // ---- small exports, written inline ----

    /**
     * An issue as Burp writes it: a null type, name, host, path, location, severity or confidence writes none. The background
     * and the request and response are what makes up most of a real export, and what the parser must not hold.
     */
    protected static String issue(String type, String name, String host, String path, String location, String severity,
            String confidence) {
        return "<issue><serialNumber>1</serialNumber>" + element("type", type) + element("name", name)
                + (host == null ? "" : "<host ip=\"192.0.2.10\">" + host + "</host>") + (path == null ? ""
                : "<path><![CDATA[" + path + "]]></path>")
                + (location == null ? "" : "<location><![CDATA[" + location + "]]></location>")
                + element("severity", severity) + element("confidence", confidence)
                + "<issueBackground><![CDATA[<p>Invented.</p>]]></issueBackground>"
                + "<requestresponse><request base64=\"true\"><![CDATA[SW52ZW50ZWQ=]]></request>"
                + "<response base64=\"true\"><![CDATA[SW52ZW50ZWQ=]]></response></requestresponse></issue>";
    }

    /** A High, Certain issue of this type at the root of the application: the plainest Finding. */
    protected static String issue(String type, String host, String path) {
        return issue(type, "Issue " + type, host, path, path, "High", "Certain");
    }

    private static String element(String name, String text) {
        return text == null ? "" : "<" + name + ">" + text + "</" + name + ">";
    }

    /** Burp's own DOCTYPE, as it writes it (only what the parser may meet in one). */
    static final String BURP_DOCTYPE = "<!DOCTYPE issues [\n<!ELEMENT issues (issue*)>\n"
            + "<!ATTLIST issues burpVersion CDATA \"\">\n<!ATTLIST issues exportTime CDATA \"\">\n"
            + "<!ELEMENT issue (type, name, host, path, location, severity, confidence)>\n"
            + "<!ELEMENT type (#PCDATA)>\n<!ATTLIST host ip CDATA \"\">\n]>\n";

    /** An export of these issues, with Burp's DOCTYPE and this export time (none writes none). */
    protected static byte[] burpAt(String exportTime, String... issues) {
        return export(BURP_DOCTYPE, exportTime, issues);
    }

    /** An export of these issues, exported at the time of the sample. */
    protected static byte[] burp(String... issues) {
        return burpAt("Tue Jul 20 10:00:00 SGT 2027", issues);
    }

    /** An export of these issues under this DOCTYPE (none is the empty text). */
    protected static byte[] export(String doctype, String exportTime, String... issues) {
        return utf8("<?xml version=\"1.0\"?>\n" + doctype + "<issues burpVersion=\"2023.1.2\""
                + (exportTime == null ? "" : " exportTime=\"" + exportTime + "\"") + ">" + String.join("", issues)
                + "</issues>");
    }
}
