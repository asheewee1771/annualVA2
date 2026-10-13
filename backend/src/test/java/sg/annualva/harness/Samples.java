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
                  <arf:reports/>
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

    /** Burp's export starts with a DOCTYPE that declares its own elements: it must be accepted, and never obeyed. */
    public static byte[] burp() {
        return utf8("""
                <?xml version="1.0"?>
                <!DOCTYPE issues [
                <!ELEMENT issues (issue*)>
                <!ATTLIST issues burpVersion CDATA "">
                <!ATTLIST issues exportTime CDATA "">
                <!ELEMENT issue (name)>
                <!ELEMENT name (#PCDATA)>
                ]>
                <issues burpVersion="2023.1" exportTime="Mon Jul 20 10:00:00 SGT 2027">
                  <issue><name>Throwaway issue</name></issue>
                </issues>
                """);
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
        byte[] base = dependencyCheckXml();
        String open = "<!--";
        String close = "-->";
        int padding = bytes - base.length - open.length() - close.length();
        StringBuilder text = new StringBuilder(new String(base, StandardCharsets.UTF_8));
        text.append(open).append("x".repeat(Math.max(0, padding))).append(close);
        return utf8(text.toString());
    }
}
