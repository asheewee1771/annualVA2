package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 41 share (stories 17 and 68): the calendar as the shell reads it, the SMO inbox of one VA
 * Cycle, the close a VA POC's screen asks for, and one pass of the lifecycle job, run by hand after the clock is moved as
 * the scheduler would. VA Cycle 2027's Q4 ends on Sunday 6 February 2028, Cycle 2028's Q1 starts on Monday 7 February
 * 2028, and Cycle 2027 closes at the end of Sunday 5 March 2028 unless a VA POC closes it earlier.
 */
abstract class CycleLifecycleSeam1 extends EndorseSeam1 {

    static final String NOT_ENDORSED = "NOT_ENDORSED";
    static final String INBOX = "/api/dashboards/smo-inbox";

    /** One pass of the lifecycle job: opens a Cycle whose Q1 has started and closes one past its close date. */
    protected void lifecycle() {
        arrange.cycleLifecycle();
    }

    /**
     * The ids of the newest entries of an action that is about no System (a Cycle's opening or close), as the second VA
     * POC reads them. The audit log is shared by the suite and append-only, so a test compares what it wrote with what
     * was there before ({@link #writtenSince}).
     */
    protected Set<String> auditIds(String action) {
        Set<String> ids = new HashSet<>();
        newest(action).forEach(entry -> ids.add(entry.get("id").asString()));
        return ids;
    }

    /** The entries of an action written since these ids were read, newest first. */
    protected List<JsonNode> writtenSince(String action, Set<String> before) {
        List<JsonNode> since = new ArrayList<>();
        newest(action).forEach(entry -> {
            if (!before.contains(entry.get("id").asString())) {
                since.add(entry);
            }
        });
        return since;
    }

    private JsonNode newest(String action) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=" + action);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    /** The Cycles as the shell reads them. */
    protected static JsonNode cycles(ApiClient client) {
        var response = client.get("/api/cycles");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** One Cycle of the list. */
    protected static JsonNode cycleOf(JsonNode cycles, int year) {
        for (JsonNode cycle : cycles.get("cycles")) {
            if (cycle.get("year").asInt() == year) {
                return cycle;
            }
        }
        throw new AssertionError("no Cycle " + year + " in " + cycles);
    }

    /** The years the list marks open, oldest first. */
    protected static List<Integer> openYears(JsonNode cycles) {
        List<Integer> open = new ArrayList<>();
        cycles.get("cycles").forEach(cycle -> {
            if (cycle.get("open").asBoolean()) {
                open.add(cycle.get("year").asInt());
            }
        });
        return open;
    }

    protected static JsonNode inbox(ApiClient client, int cycle) {
        var response = client.get(INBOX + "?cycle=" + cycle);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The System IDs an inbox lists in the Quarters the app runs, in order. */
    protected static List<String> systemIds(JsonNode inbox) {
        List<String> ids = new ArrayList<>();
        inbox.get("systems").forEach(system -> ids.add(system.get("systemId").asString()));
        return ids;
    }

    protected static JsonNode itemOf(JsonNode inbox, String systemId) {
        for (JsonNode system : inbox.get("systems")) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no System " + systemId + " in " + inbox);
    }

    protected static ApiClient.Response close(ApiClient client, int year) {
        return client.postJson("/api/cycles/" + year + "/close", "{}");
    }

    /** Closes the Cycle and expects it closed (200); the Cycle as answered. */
    protected static JsonNode closed(ApiClient client, int year) {
        var response = close(client, year);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }
}
