package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the Cyber Manager's dashboard tests share (stories 48 and 49): the inbox and the Quarter board read through the
 * HTTP API, a VA Submission started by its SMO/Tech Lead, and Findings entered by hand and proposed, so a test can count
 * what the Cyber Manager has confirmed and what awaits them. The clock defaults to the first day of Q3 of VA Cycle 2027.
 */
abstract class CyberManagerSeam1 extends InboxSeam1 {

    static final String CM_INBOX = "/api/dashboards/cm-inbox";
    static final String BOARD = "/api/dashboards/quarter-board";
    static final String CM_OTHER = "inbox.cm.other@ci.annualva.test";

    protected static JsonNode cmInbox(ApiClient client) {
        return ok(client, CM_INBOX);
    }

    protected static JsonNode board(ApiClient client, String query) {
        return ok(client, BOARD + query);
    }

    protected static JsonNode ok(ApiClient client, String path) {
        var response = client.get(path);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The System IDs listed under a heading, in the order the server gave them. */
    protected static List<String> systemIds(JsonNode items) {
        var ids = new ArrayList<String>();
        items.forEach(item -> ids.add(item.get("systemId").asString()));
        return ids;
    }

    /** The SMO/Tech Lead starts the VA Submission of one of their Systems; its id. */
    protected static String started(ApiClient smo, String systemId) {
        String system = item(inbox(smo), "systems", systemId).get("id").asString();
        var response = smo.postJson("/api/submissions", "{\"systemId\":\"" + system + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response).get("id").asString();
    }

    /**
     * The SMO/Tech Lead marks the Dependency-Check Scan Report for Findings entered by hand and enters this many; their
     * ids, in the order entered.
     */
    protected static List<String> enteredFindings(ApiClient smo, String submissionId, int count) {
        var mode = smo.putJson("/api/submissions/" + submissionId + "/reports/DEPENDENCY_CHECK/mode",
                "{\"mode\":\"MANUAL\",\"justification\":\"The tool exports PDF only.\"}");
        assertThat(mode.status()).as(mode.body()).isEqualTo(200);
        var ids = new ArrayList<String>();
        for (int n = 1; n <= count; n++) {
            var response = smo.postJson("/api/submissions/" + submissionId + "/findings/manual",
                    "{\"reportType\":\"DEPENDENCY_CHECK\",\"title\":\"Entered " + n + "\",\"identifier\":\"CVE-2027-000"
                            + n + "\",\"packageOrRule\":\"lib-" + n + " 1.0\",\"severity\":\"HIGH\"}");
            assertThat(response.status()).as(response.body()).isEqualTo(201);
            ids.add(ApiClient.json(response).get("id").asString());
        }
        return ids;
    }

    /** The SMO/Tech Lead proposes Remediated for a Finding: it then awaits the Cyber Manager's review. */
    protected static void proposed(ApiClient smo, String findingId) {
        var response = smo.postJson("/api/findings/" + findingId + "/proposals",
                "{\"status\":\"REMEDIATED\",\"evidence\":\"Upgraded.\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }
}
