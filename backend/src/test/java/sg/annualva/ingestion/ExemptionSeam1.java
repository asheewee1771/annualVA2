package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 40 share (stories 43 to 46): the calls the SMO/Tech Lead's request form and the VA POC's
 * Exemption queue make. Q3 of VA Cycle 2027 runs from Monday 2 August to Sunday 31 October 2027, so a VA done from 2 May
 * to 1 August 2027 counts as a prior VA, and one scheduled from 1 November 2027 to 31 January 2028 as a scheduled VA.
 */
abstract class ExemptionSeam1 extends NotificationSeam1 {

    static final String PRIOR_VA = "PRIOR_VA";
    static final String SCHEDULED_VA = "SCHEDULED_VA";
    static final String EXEMPTION_REQUESTED = "EXEMPTION_REQUESTED";
    static final String EXEMPTED = "EXEMPTED";
    static final String QUEUE = "/api/dashboards/exemption-queue";
    static final String DECIDE_INTENT = "exemption-decide";

    protected static String exemptionsOf(String systemId) {
        return "/api/systems/" + systemId + "/exemptions";
    }

    /** The body of a request; a null field is left out as JSON null. */
    protected static String exemption(@Nullable String basis, @Nullable String vaDate, @Nullable String evidence,
            @Nullable String evidenceFileId) {
        return "{\"basis\":" + quoted(basis) + ",\"vaDate\":" + quoted(vaDate) + ",\"evidence\":" + quoted(evidence)
                + ",\"evidenceFileId\":" + quoted(evidenceFileId) + "}";
    }

    /** A prior VA done on 15 July 2027, inside the 3 months before Q3, with its report named in the evidence. */
    protected static String priorVa() {
        return exemption(PRIOR_VA, "2027-07-15", "Third-party VA by Acme, report ref AC-2027-07.", null);
    }

    /** A VA scheduled for 15 November 2027, inside the 3 months after Q3. */
    protected static String scheduledVa() {
        return exemption(SCHEDULED_VA, "2027-11-15", "Third-party VA booked with Acme, PO 4471.", null);
    }

    protected static ApiClient.Response request(ApiClient client, String systemId, String json) {
        return client.postJson(exemptionsOf(systemId), json);
    }

    /** Requests the Exemption and expects it to be kept (201); the Exemption as answered. */
    protected static JsonNode requested(ApiClient client, String systemId, String json) {
        var response = request(client, systemId, json);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    protected static ApiClient.Response approve(ApiClient client, String exemptionId) {
        return client.postJson("/api/exemptions/" + exemptionId + "/approve", "{}");
    }

    protected static ApiClient.Response reject(ApiClient client, String exemptionId) {
        return client.postJson("/api/exemptions/" + exemptionId + "/reject", "{}");
    }

    /** Approves and expects 200; the Exemption as answered. */
    protected static JsonNode approved(ApiClient client, String exemptionId) {
        var response = approve(client, exemptionId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** Rejects and expects 200; the Exemption as answered. */
    protected static JsonNode rejected(ApiClient client, String exemptionId) {
        var response = reject(client, exemptionId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The VA POC's Exemption queue for the Cycle running today: the pending requests. */
    protected static JsonNode queue(ApiClient client) {
        var response = client.get(QUEUE);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("requests");
    }

    /** The SMO/Tech Lead's inbox row of this System, by its System ID. */
    protected static JsonNode inboxRow(ApiClient smo, String systemCode) {
        var response = smo.get("/api/dashboards/smo-inbox");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode system : ApiClient.json(response).get("systems")) {
            if (system.get("systemId").asString().equals(systemCode)) {
                return system;
            }
        }
        throw new AssertionError("no " + systemCode + " in the inbox");
    }

    /** The actions of the notifications of this person, newest first, that are not yet handled. */
    protected static List<String> openActions(ApiClient client) {
        List<String> open = new ArrayList<>();
        notifications(client).forEach(item -> {
            if (item.get("handledBy").isNull()) {
                open.add(item.get("action").asString());
            }
        });
        return open;
    }
}
