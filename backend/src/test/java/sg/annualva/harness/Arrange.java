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

    /** One pass of the Overdue sweep, which a test runs by hand after moving the clock, because scheduling is off in tests. */
    public void overdueSweep() {
        assertThat(client.post("/api/test/jobs/overdue").status()).isEqualTo(200);
    }

    /** One pass of the export job, which produces queued exports and removes expired ones (ticket 44). */
    public void exports() {
        assertThat(client.post("/api/test/jobs/exports").status()).isEqualTo(200);
    }

    /** Makes what a queued export will hold unreadable, as a row written before its shape changed would be. */
    public void unreadableExport(String exportId) {
        assertThat(client.post("/api/test/exports/" + exportId + "/unreadable").status()).isEqualTo(200);
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

    /** Makes every write of a notification fail in the database ("on"); "off" removes the fault. */
    public void notificationFault(String mode) {
        assertThat(client.post("/api/test/faults/notification/" + mode).status()).isEqualTo(200);
    }

    /** Rejects a file as another pass of the scan job would have, without touching its bytes. */
    public void rejectFile(String fileId) {
        assertThat(client.post("/api/test/report-files/" + fileId + "/rejected").status()).isEqualTo(200);
    }

    /** Sets the review state of a Finding's current proposal directly, in a state the review engine (ticket 28) does not review in. */
    public void findingReview(String findingId, String state) {
        assertThat(client.post("/api/test/findings/" + findingId + "/review/" + state).status()).isEqualTo(200);
    }

    /** Sets the review state of a not-applicable justification directly, in a state the review engine (ticket 28) does not review in. */
    public void reportReview(String submissionId, String reportType, String state) {
        assertThat(client.post("/api/test/submissions/" + submissionId + "/reports/" + reportType + "/review/" + state)
                .status()).isEqualTo(200);
    }

    /** Puts a VA Submission in a state no command enters yet. */
    public void submissionState(String submissionId, String state) {
        assertThat(client.post("/api/test/submissions/" + submissionId + "/state/" + state).status()).isEqualTo(200);
    }

    /** Marks a VA Submission endorsed after its Quarter's last day, as the endorse command (ticket 34) does. */
    public void endorsedLate(String submissionId) {
        assertThat(client.postJson("/api/test/submissions/" + submissionId + "/milestones", "{\"endorsedLate\":true}")
                .status()).isEqualTo(200);
    }

    /**
     * Sets when a VA Submission reached its milestones (instants such as {@code 2027-08-10T02:00:00Z}), as submit, pass
     * to endorsement (ticket 30) and endorse (ticket 34) write them; a null one is left as it is.
     */
    public void milestones(String submissionId, @Nullable String submittedAt, @Nullable String passedToEndorsementAt,
            @Nullable String endorsedAt) {
        String body = "{\"submittedAt\":" + quoted(submittedAt) + ",\"passedToEndorsementAt\":"
                + quoted(passedToEndorsementAt) + ",\"endorsedAt\":" + quoted(endorsedAt) + "}";
        assertThat(client.postJson("/api/test/submissions/" + submissionId + "/milestones", body).status())
                .isEqualTo(200);
    }

    private static String quoted(@Nullable String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** Adds the next version of the Declaration wording, not a placeholder, as a later migration will; returns it. */
    public int declarationVersion() {
        var response = client.post("/api/test/declaration-text");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("version").asInt();
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
