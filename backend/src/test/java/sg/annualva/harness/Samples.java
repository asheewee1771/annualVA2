package sg.annualva.harness;

import java.nio.charset.StandardCharsets;

/**
 * Minimal Scan Report files for the upload pipeline tests: just enough of each format for the type allowlist and the
 * schema check. They name only throwaway targets (never a real System or host). The parsers' tickets bring richer
 * sample exports of their own; the structure of these is what the pipeline reads and nothing more.
 */
public final class Samples {

    private Samples() {}

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] dependencyCheckXml() {
        return utf8("""
                <?xml version="1.0" encoding="UTF-8"?>
                <analysis xmlns="https://jeremylong.github.io/DependencyCheck/dependency-check.2.5.xsd">
                  <scanInfo><engineVersion>9.0.0</engineVersion></scanInfo>
                  <projectInfo><name>throwaway-project</name><reportDate>2027-07-20T10:00:00Z</reportDate></projectInfo>
                  <dependencies></dependencies>
                </analysis>
                """);
    }

    public static byte[] dependencyCheckJson() {
        return utf8("""
                {"reportSchema":"1.1","scanInfo":{"engineVersion":"9.0.0"},
                 "projectInfo":{"name":"throwaway-project","reportDate":"2027-07-20T10:00:00Z"},
                 "dependencies":[{"fileName":"throwaway.jar"}]}
                """);
    }

    public static byte[] xccdfResults() {
        return utf8("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Benchmark xmlns="http://checklists.nist.gov/xccdf/1.2" id="xccdf_throwaway_benchmark">
                  <TestResult id="xccdf_throwaway_result" end-time="2027-07-20T10:00:00"/>
                </Benchmark>
                """);
    }

    public static byte[] arf() {
        return utf8("""
                <?xml version="1.0" encoding="UTF-8"?>
                <arf:asset-report-collection xmlns:arf="http://scap.nist.gov/schema/asset-reporting-format/1.1">
                  <arf:reports>
                    <arf:report id="xccdf1"><arf:content>
                      <TestResult xmlns="http://checklists.nist.gov/xccdf/1.2" id="xccdf_throwaway_result"
                          end-time="2027-07-20T10:00:00"><target>throwaway-host.invalid</target></TestResult>
                    </arf:content></arf:report>
                  </arf:reports>
                </arf:asset-report-collection>
                """);
    }

    public static byte[] nessus() {
        return utf8("""
                <?xml version="1.0" ?>
                <NessusClientData_v2>
                  <Policy/>
                  <Report name="throwaway"><ReportHost name="host-1.invalid"/></Report>
                </NessusClientData_v2>
                """);
    }

    /**
     * A small Burp export, which starts with a DOCTYPE that declares its own elements (accepted, and never obeyed), with one
     * issue: type 1, https://throwaway.invalid/, High, Certain.
     */
    public static byte[] burp() {
        return utf8("""
                <?xml version="1.0"?>
                <!DOCTYPE issues [
                <!ELEMENT issues (issue*)>
                <!ATTLIST issues burpVersion CDATA "">
                <!ATTLIST issues exportTime CDATA "">
                <!ELEMENT issue (type, name, host, path, location, severity, confidence)>
                <!ELEMENT type (#PCDATA)>
                <!ELEMENT name (#PCDATA)>
                <!ELEMENT host (#PCDATA)>
                <!ELEMENT path (#PCDATA)>
                <!ELEMENT location (#PCDATA)>
                <!ELEMENT severity (#PCDATA)>
                <!ELEMENT confidence (#PCDATA)>
                ]>
                <issues burpVersion="2023.1" exportTime="Tue Jul 20 10:00:00 SGT 2027">
                  <issue><type>1</type><name>Throwaway issue</name><host>https://throwaway.invalid</host>
                    <path>/</path><location>/</location><severity>High</severity><confidence>Certain</confidence></issue>
                </issues>
                """);
    }

    /** The Burp Suite sample export, five issues of four Findings (a reconstruction: see its provenance note). */
    public static byte[] burpSample() {
        return resource("samples/burp/issues-export.xml");
    }

    /** The Dependency-Check XML sample export (a reconstruction of a throwaway project's report: see its provenance note). */
    public static byte[] dependencyCheckSampleXml() {
        return resource("samples/dependency-check/dependency-check-report.xml");
    }

    /** The Dependency-Check JSON sample export: the same Findings as the XML one. */
    public static byte[] dependencyCheckSampleJson() {
        return resource("samples/dependency-check/dependency-check-report.json");
    }

    /** The CIS-CAT Pro XCCDF results sample (a reconstruction: see its provenance note). */
    public static byte[] cisCatXccdfSample() {
        return resource("samples/scap/cis-cat-xccdf-results.xml");
    }

    /** The OpenSCAP ARF sample, two hosts (a reconstruction: see its provenance note). */
    public static byte[] openScapArfSample() {
        return resource("samples/scap/openscap-arf.xml");
    }

    /** The Nessus compliance audit sample, two hosts (a reconstruction: see its provenance note). */
    public static byte[] nessusComplianceSample() {
        return resource("samples/nessus/compliance-audit.nessus");
    }

    private static byte[] resource(String path) {
        try (var in = Samples.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("No sample " + path);
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read sample " + path, e);
        }
    }

    /** A well-formed document of a type no Scan Report accepts. */
    public static byte[] html() {
        return utf8("<!DOCTYPE html><html><body><p>report</p></body></html>");
    }

    /** What a PNG starts with: not XML, not JSON. */
    public static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    /** A Dependency-Check XML report whose bytes are padded to this length by a comment, which keeps it well-formed. */
    public static byte[] dependencyCheckXmlOfSize(int bytes) {
        return paddedTo(dependencyCheckXml(), bytes);
    }

    /** An XML document padded to this length by a comment after its root element, which keeps it well-formed. */
    public static byte[] paddedTo(byte[] base, int bytes) {
        String open = "<!--";
        String close = "-->";
        int padding = bytes - base.length - open.length() - close.length();
        StringBuilder text = new StringBuilder(new String(base, StandardCharsets.UTF_8));
        text.append(open).append("x".repeat(Math.max(0, padding))).append(close);
        return utf8(text.toString());
    }
}
