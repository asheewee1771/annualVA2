package sg.annualva.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Sets up the state that later slices will create through their own endpoints, using the
 * test-only endpoints under /api/test: Systems as a System Register apply would create them, and
 * role grants as a Register apply, configuration or an Auditor grant would write them. Tests then
 * assert only on what the real endpoints answer.
 */
public final class Arrange {

    private final ApiClient client;

    Arrange(ApiClient client) {
        this.client = client;
    }

    /** A System that exists; returns its id. */
    public String system() {
        var response = client.postJson("/api/test/systems", "{}");
        assertThat(response.status()).isEqualTo(200);
        return ApiClient.json(response).get("id").asString();
    }

    /**
     * A System as the Register holds it, standing in for what a Register apply will write (ticket 10); returns its id.
     * The Register is shared by the whole suite, so a test that arranges Systems calls {@link #clearRegister()} first
     * and last.
     */
    public String registered(String code, String name, String smoEmail, String cyberManagerEmail, int quarter,
            boolean retired) {
        String json = "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"smoEmail\":\"" + smoEmail
                + "\",\"cyberManagerEmail\":\"" + cyberManagerEmail + "\",\"quarter\":" + quarter
                + ",\"retired\":" + retired + "}";
        var response = client.postJson("/api/test/register-systems", json);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("id").asString();
    }

    /** Empties the System Register and its previews, so a test starts from none. */
    public void clearRegister() {
        assertThat(client.post("/api/test/register-reset").status()).isEqualTo(200);
    }

    /** A person known by email only, as the System Register creates them; returns the user id. */
    public String preRegister(String email) {
        var response = client.postJson("/api/test/users", "{\"email\":\"" + email + "\"}");
        assertThat(response.status()).isEqualTo(200);
        return ApiClient.json(response).get("uuid").asString();
    }

    /** A role granted by Register apply (per System roles) or otherwise by email; returns the grant id. */
    public String grant(String role, String email, @Nullable String systemId) {
        return grant(role, email, systemId, "REGISTER", null);
    }

    public String grant(String role, String email, @Nullable String systemId, String source,
            @Nullable String expiresAt) {
        String json = "{\"role\":\"" + role + "\",\"email\":\"" + email + "\""
                + (systemId == null ? "" : ",\"systemId\":\"" + systemId + "\"")
                + ",\"source\":\"" + source + "\""
                + (expiresAt == null ? "" : ",\"expiresAt\":\"" + expiresAt + "\"") + "}";
        var response = client.postJson("/api/test/grants", json);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("id").asString();
    }

    /** Attempts a grant and returns the HTTP status instead of asserting success. */
    public int tryGrant(String role, String email, @Nullable String systemId, String source) {
        String json = "{\"role\":\"" + role + "\",\"email\":\"" + email + "\",\"source\":\"" + source + "\""
                + (systemId == null ? "" : ",\"systemId\":\"" + systemId + "\"") + "}";
        return client.postJson("/api/test/grants", json).status();
    }

    public void revoke(String grantId) {
        change(grantId, "revoke");
    }

    public void suspend(String grantId) {
        change(grantId, "suspend");
    }

    public void lapse(String grantId) {
        change(grantId, "lapse");
    }

    /** The grants held under one email, as the role store has them. */
    public JsonNode grantsOf(String email) {
        var response = client.get("/api/test/grants?email=" + email);
        assertThat(response.status()).isEqualTo(200);
        return ApiClient.json(response);
    }

    private void change(String grantId, String change) {
        assertThat(client.post("/api/test/grants/" + grantId + "/" + change).status()).isEqualTo(200);
    }
}
