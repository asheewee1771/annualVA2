package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import sg.annualva.register.RegisterSeam1;
import tools.jackson.databind.JsonNode;

/**
 * What the inbox tests share: the Register is filled the way a VA POC fills it (an upload and its apply), people sign in
 * through the HTTP API, and the clock is put where the test needs it before anyone signs in (a session idles out by
 * the application clock). The default clock is 09:00 on Monday 2 August 2027, the first day of Q3 of VA Cycle 2027,
 * which is the first live Quarter: Q1 and Q2 of that Cycle are Handled outside the app.
 */
abstract class InboxSeam1 extends RegisterSeam1 {

    static final String INBOX = "/api/dashboards/smo-inbox";
    static final String SMO_A = "inbox.smo.a@ci.annualva.test";
    static final String SMO_B = "inbox.smo.b@ci.annualva.test";
    static final String CM = "inbox.cm@ci.annualva.test";
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    /** Puts the clock at 09:00 Singapore time on this business day. */
    protected void today(String businessDay) {
        Instant instant = LocalDate.parse(businessDay).atTime(LocalTime.of(9, 0)).atZone(SINGAPORE).toInstant();
        clock.set(instant);
    }

    protected static JsonNode inbox(ApiClient client) {
        var response = client.get(INBOX);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    protected static JsonNode inbox(ApiClient client, int cycle) {
        var response = client.get(INBOX + "?cycle=" + cycle);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The System IDs listed under a heading of the inbox ({@code systems} or {@code handledOutsideApp}), in order. */
    protected static List<String> listed(JsonNode inbox, String heading) {
        var ids = new ArrayList<String>();
        inbox.get(heading).forEach(system -> ids.add(system.get("systemId").asString()));
        return ids;
    }

    protected static JsonNode item(JsonNode inbox, String heading, String systemId) {
        for (JsonNode system : inbox.get(heading)) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no System " + systemId + " under " + heading + " in " + inbox);
    }
}
