package sg.annualva.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * IM8 lm-4 and lm-19 for starting a VA Submission: a start is one entry about the System and the VA Submission by the
 * SMO/Tech Lead, a refused start is an entry with a fixed reason, and no entry, log line or error body holds a System
 * name, an email or any text from the request.
 */
@ExtendWith(OutputCaptureExtension.class)
class SubmissionAuditTest extends SubmissionSeam1 {

    private static final Set<String> KEYS = Set.of("state_to", "reason");

    private void registerSystems() {
        registered(
                List.of(listed("SYS-U1", "Audit one", SMO_A, CM, "Q3"),
                        listed("SYS-U0", "Audit zero", SMO_A, CM, "Q1"),
                        listed("SYS-U2", "Audit two", SMO_B, CM, "Q3")),
                List.of());
    }

    private JsonNode entries(String system) {
        var response = vaPoc().get("/api/audit-entries?size=200&action=SUBMISSION_STARTED&system=" + system);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    private static void assertNoContent(JsonNode entry) {
        String text = entry.toString().toLowerCase();
        assertThat(text).doesNotContain("audit one").doesNotContain("sys-u").doesNotContain("@ci.annualva.test")
                .doesNotContain("sentinel");
        entry.get("detail").propertyNames().forEach(key -> assertThat(KEYS).contains(key));
    }

    @Test
    void aStartIsOneEntryAboutTheSystemAndTheVaSubmissionByTheSmo() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String system = idOf("SYS-U1");
        String actor = userId(smo);

        JsonNode submission = started(smo, system);

        JsonNode entries = entries(system);
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(actor);
        assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(entry.get("systemId").asString()).isEqualTo(system);
        assertThat(entry.get("submissionId").asString()).isEqualTo(submission.get("id").asString());
        assertThat(entry.get("detail").get("state_to").asString()).isEqualTo("DRAFT");
        assertNoContent(entry);
    }

    @Test
    void aSecondStartIsARefusedEntryAndAddsNoSuccessEntry() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String system = idOf("SYS-U1");
        started(smo, system);

        assertThat(start(smo, system).status()).isEqualTo(409);

        JsonNode entries = entries(system);
        assertThat(entries).hasSize(2);
        JsonNode refused = entries.get(0);
        assertThat(refused.get("outcome").asString()).as("newest first").isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("already-started");
        assertThat(refused.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(refused.get("systemId").asString()).isEqualTo(system);
        assertThat(refused.get("submissionId").isNull()).isTrue();
        assertThat(entries.get(1).get("outcome").asString()).isEqualTo("SUCCESS");
        assertNoContent(refused);
    }

    @Test
    void aSystemHandledOutsideTheAppIsARefusedEntryWithItsOwnReason() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String system = idOf("SYS-U0");

        assertThat(start(smo, system).status()).isEqualTo(409);

        JsonNode entries = entries(system);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("quarter-handled-outside-app");
        assertNoContent(entries.get(0));
    }

    @Test
    void aStartByAnotherSmoOrAByCyberManagerWritesNoStartEntry() {
        registerSystems();
        String system = idOf("SYS-U1");

        assertThat(start(signedInAs(SMO_B), system).status()).as("another SMO: not found").isEqualTo(404);
        assertThat(start(signedInAs(CM), system).status()).as("a Cyber Manager: forbidden").isEqualTo(403);
        assertThat(start(signedInAs(SMO_A), UUID.randomUUID().toString()).status()).isEqualTo(404);

        assertThat(entries(system)).isEmpty();
    }

    @Test
    void nothingFromTheRequestReachesTheAuditLogTheApplicationLogOrAnErrorBody(CapturedOutput output) {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String system = idOf("SYS-U1");
        started(smo, system);

        var again = smo.postJson(SUBMISSIONS, "{\"systemId\":\"" + system + "\",\"note\":\"CVE-9999-SENTINEL\"}");

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999");
        entries(system).forEach(SubmissionAuditTest::assertNoContent);
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999");
    }
}
