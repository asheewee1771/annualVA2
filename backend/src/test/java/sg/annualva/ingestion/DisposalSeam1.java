package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 49 share (story 87): the retention job, run by hand one pass at a time as the scheduler would
 * (it executes an approved disposal list, then lists what is due), the disposal lists as a VA POC's Disposal screen reads
 * them, and the approve call it makes. VA Cycle 2027 is closed early on 14 February 2028 by these tests, so its records
 * are kept through 14 February 2031 and due on the 15th; the Cycle after it starts on 7 February 2028.
 */
@ExtendWith(OutputCaptureExtension.class)
abstract class DisposalSeam1 extends CycleLifecycleSeam1 {

    static final String DISPOSAL_LISTS = "/api/disposal-lists";

    /** The application's log, as the job writes it, so a pass can be checked for a listing that failed. */
    private CapturedOutput log;

    @BeforeEach
    void readTheLog(CapturedOutput output) {
        log = output;
    }

    /**
     * One pass of the retention job, which must have listed what is due. The job catches a listing that fails, logs it
     * ({@code disposal-list-failed}) and answers as any pass does (ticket 67), so a test that then finds nothing listed
     * could not otherwise tell "nothing is due" from "the listing crashed". The job's series cannot tell either when two
     * passes run at one instant, which these tests do, so this reads the log the pass wrote instead.
     */
    protected void retention() {
        int from = log.getAll().length();
        arrange.retention();
        assertThat(log.getAll().substring(from)).as("the listing completed").doesNotContain("disposal-list-failed");
    }

    /** The disposal lists, newest first, as this person's screen reads them (200). */
    protected static JsonNode disposalLists(ApiClient client) {
        var response = client.get(DISPOSAL_LISTS);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        return ApiClient.json(response);
    }

    /** The one list in this status, newest first. */
    protected static JsonNode listIn(ApiClient client, String status) {
        for (JsonNode list : disposalLists(client)) {
            if (list.get("status").asString().equals(status)) {
                return list;
            }
        }
        throw new AssertionError("no " + status + " disposal list");
    }

    protected static ApiClient.Response approve(ApiClient client, String listId) {
        return client.postJson(DISPOSAL_LISTS + "/" + listId + "/approve", "{}");
    }

    /** Approves the list and expects it approved (200); the list as answered. */
    protected static JsonNode approved(ApiClient client, String listId) {
        var response = approve(client, listId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** "KIND target reason" for every item of a list, in its order. */
    protected static List<String> items(JsonNode list) {
        List<String> items = new ArrayList<>();
        list.get("items").forEach(item -> items.add(item.get("kind").asString() + " "
                + (item.get("targetId").isNull() ? "-" : item.get("targetId").asString()) + " "
                + item.get("reason").asString()));
        return items;
    }

    /** A Dependency-Check report made at this instant (ISO text) that reads into two Findings, alpha and bravo. */
    protected static byte[] twoFindings(String scanned) {
        return jsonReportOf("{\"name\":\"throwaway-project\",\"reportDate\":\"" + scanned + "\"}",
                dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"));
    }

    /**
     * The Draft's other two Scan Reports: Secure Configuration entered by hand with one Finding, and Burp Suite not
     * applicable.
     */
    protected static void enteredByHandAndNotApplicable(Draft draft) {
        ApiClient smo = draft.smo();
        String sub = draft.submissionId();
        modeSet(smo, sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(smo, sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        modeSet(smo, sub, BURP_SUITE, mode(NOT_APPLICABLE, "The System has no web interface."));
    }

    /** Asks for the management summary of the Cycle running today as this VA POC and has the export job make it; its id. */
    protected String exportMade(ApiClient poc) {
        var response = poc.postJson("/api/exports", "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        arrange.exports();
        return ApiClient.json(response).get("id").asString();
    }
}
