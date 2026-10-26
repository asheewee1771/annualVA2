package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
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

    protected static List<String> roles(ApiClient client) {
        List<String> names = new ArrayList<>();
        ApiClient.json(client.get("/api/session")).get("roles").forEach(role -> names.add(role.asString()));
        return names;
    }

    /** The audit entries of one action about one grant, newest first. */
    protected static List<JsonNode> entries(ApiClient reader, String action, String grantId) {
        return AuditEntries.all(reader, "action=" + action).stream()
                .filter(entry -> entry.path("detail").path("grant_id").asString("").equals(grantId)).toList();
    }

    protected static ApiClient.Response suspend(ApiClient poc, String grantId) {
        return poc.post(HOLDERS + "/" + grantId + "/suspend");
    }

    protected static ApiClient.Response restore(ApiClient poc, String grantId) {
        return poc.post(HOLDERS + "/" + grantId + "/restore");
    }
}
