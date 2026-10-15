package sg.annualva.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Sets up the state that no public endpoint creates, using the
 * test-only endpoints under /api/test: a System that exists for another module to address, and
 * role grants in states the Register never makes (configuration, an Auditor grant, lapsed). Tests then
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
     * Empties the System Register, its previews and the grants scoped to a System, and ends every signed-in session, so
     * a test starts from none (the reset endpoint says why the sessions go). The Register is shared by the whole suite,
     * so a test that uses it calls this first and last, before it signs anyone in.
     */
    public void clearRegister() {
        assertThat(client.post("/api/test/register-reset").status()).isEqualTo(200);
    }

    /** One pass of the scan job, which a test moves forward by hand because scheduling is off in tests. */
    public void scan() {
        assertThat(client.post("/api/test/jobs/scan").status()).isEqualTo(200);
    }

    /** Makes writes moving a file to a status with this prefix fail in the database; "off" removes the fault. */
    public void reportFileFault(String prefix) {
        assertThat(client.post("/api/test/faults/report-file/" + prefix).status()).isEqualTo(200);
    }

    /**
     * Makes the write of a file's Finding occurrences fail in the database ("on"), after everything else of its settlement was
     * written, or the commit of the settlement fail ("late"), after every write of it; "off" removes the fault.
     */
    public void findingFault(String mode) {
        assertThat(client.post("/api/test/faults/finding/" + mode).status()).isEqualTo(200);
    }

    /** Rejects a file as another pass of the scan job would have, without touching its bytes. */
    public void rejectFile(String fileId) {
        assertThat(client.post("/api/test/report-files/" + fileId + "/rejected").status()).isEqualTo(200);
    }

    /** Puts a VA Submission in a state no command enters yet. */
    public void submissionState(String submissionId, String state) {
        assertThat(client.post("/api/test/submissions/" + submissionId + "/state/" + state).status()).isEqualTo(200);
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
