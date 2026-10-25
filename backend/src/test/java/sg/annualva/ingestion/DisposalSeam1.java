package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 49 share (story 87): the retention job, run by hand one pass at a time as the scheduler would
 * (it executes an approved disposal list, then lists what is due), the disposal lists as a VA POC's Disposal screen reads
 * them, and the approve call it makes. VA Cycle 2027 is closed early on 14 February 2028 by these tests, so its records
 * are kept through 14 February 2031 and due on the 15th; the Cycle after it starts on 7 February 2028.
 */
abstract class DisposalSeam1 extends CycleLifecycleSeam1 {

    static final String DISPOSAL_LISTS = "/api/disposal-lists";

    /** One pass of the retention job. */
    protected void retention() {
        arrange.retention();
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

    /** Asks for the management summary of the Cycle running today as this VA POC and has the export job make it; its id. */
    protected String exportMade(ApiClient poc) {
        var response = poc.postJson("/api/exports", "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        arrange.exports();
        return ApiClient.json(response).get("id").asString();
    }
}
