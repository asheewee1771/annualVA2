package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the role-holder review tests share (ticket 47, story 80a, IM8 ac-3 and ac-4): two Systems in the Register, the
 * people it names, and reads of the review and the audit log through the HTTP API.
 */
public abstract class RoleHolderSeam1 extends RegisterSeam1 {

    protected static final String HOLDERS = "/api/role-holders";
    protected static final String SMO_A = "holder.smo.a@ci.annualva.test";
    protected static final String SMO_B = "holder.smo.b@ci.annualva.test";
    protected static final String SMO_C = "holder.smo.c@ci.annualva.test";
    protected static final String CM = "holder.cm@ci.annualva.test";

    /** SYS-HA (SMO_A, CM) and SYS-HB (SMO_B, CM), as a VA POC's upload and apply put them. */
    protected void twoSystems() {
        registered(listed("SYS-HA", "Holder A", SMO_A, CM, "Q3"), listed("SYS-HB", "Holder B", SMO_B, CM, "Q4"));
    }

    protected static JsonNode review(ApiClient poc) {
        var response = poc.get(HOLDERS);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The one holder of this role, for this email, on the System with this System ID (null for an organisation-wide role). */
    protected static JsonNode holder(JsonNode review, String role, String email, String systemCode) {
        List<JsonNode> matching = new ArrayList<>();
        review.get("holders").forEach(holder -> {
            boolean onSystem = systemCode == null ? holder.get("systemCode").isNull()
                    : systemCode.equals(holder.get("systemCode").asString(""));
            if (holder.get("role").asString().equals(role) && holder.get("email").asString().equalsIgnoreCase(email)
                    && onSystem) {
                matching.add(holder);
            }
        });
        assertThat(matching).as(role + " " + email + " on " + systemCode).hasSize(1);
        return matching.getFirst();
    }

    protected static String grantOf(JsonNode review, String role, String email, String systemCode) {
        return holder(review, role, email, systemCode).get("id").asString();
    }

    protected static List<String> roles(ApiClient client) {
        List<String> names = new ArrayList<>();
        ApiClient.json(client.get("/api/session")).get("roles").forEach(role -> names.add(role.asString()));
        return names;
    }

    protected static String userId(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session")).get("uuid").asString();
    }

    /** The audit entries of one action about one grant, newest first. */
    protected static List<JsonNode> entries(ApiClient reader, String action, String grantId) {
        var response = reader.get("/api/audit-entries?size=200&action=" + action);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        ApiClient.json(response).get("items").forEach(entry -> {
            if (entry.path("detail").path("grant_id").asString("").equals(grantId)) {
                items.add(entry);
            }
        });
        return items;
    }

    protected static ApiClient.Response suspend(ApiClient poc, String grantId) {
        return poc.post(HOLDERS + "/" + grantId + "/suspend");
    }

    protected static ApiClient.Response restore(ApiClient poc, String grantId) {
        return poc.post(HOLDERS + "/" + grantId + "/restore");
    }
}
