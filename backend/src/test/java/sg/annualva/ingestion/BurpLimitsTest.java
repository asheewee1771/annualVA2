package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * IM8 as-1 for the Burp parser: a report is untrusted, so what one file may make the server hold is bounded. A Burp export may
 * be as large as 50 MB, nearly all of it the requests and responses of the scan, which are skipped and never built (one of
 * them can be tens of megabytes on its own); a file over the limit is refused at the upload; and one value far longer than any
 * real one is refused without being built whole. The caps on Findings and places are shared with the other parsers and are
 * proved once, for Nessus ({@code NessusLimitsTest}).
 */
class BurpLimitsTest extends BurpSeam1 {

    private static final int MEGABYTE = 1024 * 1024;

    /** An issue with only what the parser reads, at this path, and this request as a base64 text. */
    private static String withBlob(String type, String path, String blob) {
        return "<issue><serialNumber>1</serialNumber><type>" + type + "</type><name>Issue " + type + "</name><host>" + APP
                + "</host><path><![CDATA[" + path + "]]></path><location><![CDATA[" + path + "]]></location>"
                + "<severity>High</severity><confidence>Firm</confidence><requestresponse><request base64=\"true\"><![CDATA["
                + blob + "]]></request><response base64=\"true\"><![CDATA[" + blob.substring(0, Math.min(100, blob.length()))
                + "]]></response></requestresponse></issue>";
    }

    /**
     * A Burp export of about this many bytes: one issue whose request is a single base64 text of about 30 MB, then issue after
     * issue each with a request of 100 KB, and in every blob something that looks like an issue and is not.
     */
    private static byte[] exportWithBulk(int bytes, int[] issuesWritten) {
        String decoy = "</request><issue><type>decoy</type><host>https://decoy.invalid</host></issue>";
        String blob = "QUJD".repeat(25_000) + decoy;
        String head = "<?xml version=\"1.0\"?>\n" + BURP_DOCTYPE + "<issues burpVersion=\"2023.1.2\" exportTime=\"Tue Jul 20 10:00:00 SGT 2027\">";
        String tail = "</issues>";
        StringBuilder text = new StringBuilder(bytes + 1024);
        text.append(head).append(withBlob("big", "/big", "QUJD".repeat(30 * MEGABYTE / 4)));
        int issues = 1;
        while (text.length() + tail.length() + blob.length() + 600 < bytes) {
            text.append(withBlob("t" + issues, "/p" + issues, blob));
            issues++;
        }
        issuesWritten[0] = issues;
        return text.append(tail).toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBurpFileMayBeAsLargeAsItsLimitAndTheRequestsAndResponsesInItAreSkippedWhateverTheirSize() {
        Draft draft = draft();
        int[] written = new int[1];
        byte[] big = exportWithBulk(49 * MEGABYTE, written);

        JsonNode file = settledBurp(draft, "big.xml", big);

        assertThat(big.length).as("over the 25 MB an XCCDF or Dependency-Check file may be").isGreaterThan(26 * MEGABYTE);
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("BURP_XML");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(page.get("total").asInt()).as("every issue of the export, and none of the decoys in the blobs")
                .isEqualTo(written[0]);
        assertThat(page.toString()).doesNotContain("decoy");
    }

    @Test
    void aBurpFileOverItsLimitIsRefusedAtTheUploadAndNothingIsKept() {
        Draft draft = draft();
        byte[] big = Samples.paddedTo(Samples.burpSample(), 50 * MEGABYTE + 1024);

        var response = upload(draft.smo(), draft.submissionId(), BURP_SUITE, MACHINE_READABLE, "big.xml", big);

        assertThat(response.status()).as("its 50 MB limit is the request ceiling (ADR 0021)").isEqualTo(413);
        assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.QUARANTINE)).isZero();
        assertThat(sg.annualva.harness.ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))).isEmpty();
    }

    @Test
    void aSingleValueFarLongerThanAnyRealOneIsRefusedWithoutBeingBuiltWhole() {
        Draft draft = draft();
        String huge = "n".repeat(8 * MEGABYTE);

        JsonNode inAName = settledBurp(draft, "name.xml", burp(issue("t", huge, APP, "/p", "/p", "High", "Certain")));
        JsonNode inAHost = settledBurp(draft, "host.xml", burp(issue("t", "N", huge, "/p", "/p", "High", "Certain")));
        JsonNode inAPath = settledBurp(draft, "path.xml", burp(issue("t", "N", APP, huge, "/p", "High", "Certain")));

        assertThat(inAName.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(inAHost.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(inAPath.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
    }
}
