package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of replacing a Scan Report file share (story 39, ticket 21): small Dependency-Check reports written
 * inline, a first scan and a rescan of it, and the call a person's browser makes to send the rescan.
 */
abstract class ReplaceSeam1 extends ProposalSeam1 {

    static final String PKG_A = "pkg:npm/alpha@1.0.0";
    static final String PKG_B = "pkg:npm/bravo@1.0.0";
    static final String PKG_C = "pkg:npm/charlie@1.0.0";
    static final String PKG_D = "pkg:npm/delta@1.0.0";

    static String replaceOf(String submissionId, String fileId) {
        return fileAt(submissionId, fileId) + "/replace";
    }

    static ApiClient.Response replace(ApiClient client, String submissionId, String fileId, String name, byte[] content) {
        return client.postMultipart(replaceOf(submissionId, fileId), Map.of(), "file", name, "application/octet-stream",
                content);
    }

    /** Sends the rescan and expects it to be taken for scanning (202); returns its status as answered. */
    static JsonNode replaced(Draft draft, String fileId, String name, byte[] content) {
        var response = replace(draft.smo(), draft.submissionId(), fileId, name, content);
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(response.header("location")).endsWith(ApiClient.json(response).get("id").asString());
        return ApiClient.json(response);
    }

    /** Sends the rescan, then runs the job until its verdict is in. */
    JsonNode replacedAndSettled(Draft draft, String fileId, String name, byte[] content) {
        String id = replaced(draft, fileId, name, content).get("id").asString();
        pass();
        pass();
        return status(draft.smo(), draft.submissionId(), id);
    }

    /** One dependency with one vulnerability, at the path given, rated by its CVSS v3 score. */
    static String dependency(String purl, String path, String cve, String score) {
        return jsonDependency(path.substring(path.lastIndexOf('/') + 1), path, purl, jsonVulnerability(cve, v3(score)));
    }

    /** The first scan: alpha (Critical), bravo (High) and charlie (Medium). */
    static byte[] firstScan() {
        return jsonReport(dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"),
                dependency(PKG_C, "/app/charlie.js", "CVE-2027-0003", "5.0"));
    }

    /** The rescan: alpha now rated Medium at a new path, bravo as before, charlie gone, delta new. */
    static byte[] rescan() {
        return jsonReport(dependency(PKG_A, "/srv/alpha.js", "CVE-2027-0001", "5.0"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"),
                dependency(PKG_D, "/app/delta.js", "CVE-2027-0004", "9.1"));
    }

    /** A Draft with the first scan read into its Findings; returns the file's id. */
    String firstScanOf(Draft draft) {
        JsonNode file = settledDc(draft, "first.json", firstScan());
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        return file.get("id").asString();
    }

    /** The Findings as the SMO reads them, identity key to "SEVERITY/places/status proposed or -". */
    static Map<String, String> state(Draft draft) {
        Map<String, String> state = new java.util.LinkedHashMap<>();
        for (JsonNode item : findings(draft.smo(), draft.submissionId()).get("items")) {
            JsonNode proposal = item.get("currentProposal");
            state.put(item.get("identityKey").asString(), item.get("severity").asString() + "/"
                    + item.get("occurrenceCount").asInt() + "/" + (proposal.isNull() ? "-" : proposal.get("status").asString()));
        }
        return state;
    }
}
