package sg.annualva.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import sg.annualva.harness.ApiClient;
import sg.annualva.register.RegisterSeam1;
import tools.jackson.databind.JsonNode;

/**
 * What the VA Submission tests share: the Register is filled the way a VA POC fills it (an upload and its apply), people
 * sign in through the HTTP API, and the clock is put where the test needs it before anyone signs in (a session idles out
 * by the application clock). The default clock is 09:00 on Monday 2 August 2027, the first day of Q3 of VA Cycle 2027,
 * which is the first live Quarter: Q1 and Q2 of that Cycle are Handled outside the app.
 */
public abstract class SubmissionSeam1 extends RegisterSeam1 {

    public static final String SUBMISSIONS = "/api/submissions";
    public static final String SMO_A = "sub.smo.a@ci.annualva.test";
    public static final String SMO_B = "sub.smo.b@ci.annualva.test";
    public static final String CM = "sub.cm@ci.annualva.test";
    public static final String CM_OTHER = "sub.cm.other@ci.annualva.test";
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    /** Puts the clock at 09:00 Singapore time on this business day. */
    protected void today(String businessDay) {
        Instant instant = LocalDate.parse(businessDay).atTime(LocalTime.of(9, 0)).atZone(SINGAPORE).toInstant();
        clock.set(instant);
    }

    /** The System's own id (not its System ID), as a VA POC sees it in the Register. */
    protected String idOf(String systemId) {
        for (JsonNode system : ApiClient.json(vaPoc().get("/api/systems"))) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system.get("id").asString();
            }
        }
        throw new AssertionError("no System " + systemId);
    }

    protected static ApiClient.Response start(ApiClient client, String systemUuid) {
        return client.postJson(SUBMISSIONS, "{\"systemId\":\"" + systemUuid + "\"}");
    }

    protected static ApiClient.Response start(ApiClient client, String systemUuid, int cycle) {
        return client.postJson(SUBMISSIONS, "{\"systemId\":\"" + systemUuid + "\",\"cycle\":" + cycle + "}");
    }

    /** Starts a VA Submission and expects it to be created. */
    protected static JsonNode started(ApiClient client, String systemUuid) {
        var response = start(client, systemUuid);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    protected static ApiClient.Response read(ApiClient client, String submissionId) {
        return client.get(SUBMISSIONS + "/" + submissionId);
    }
}
