package sg.annualva.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 84 and IM8 lm-4 for reading one VA Submission (reviewer-decisions.md): a read by a Cyber Manager or a VA POC is
 * one {@code SUBMISSION_VIEWED} entry about the System and the VA Submission; a read by the System's own SMO/Tech Lead
 * writes none; a 404 or a 403 is not recorded by this path. An entry holds ids only, never a name, an email, a System
 * ID or anything from the request.
 */
@ExtendWith(OutputCaptureExtension.class)
class SubmissionViewAuditTest extends SubmissionSeam1 {

    private void registerSystems() {
        registered(
                List.of(listed("SYS-V1", "View one", SMO_A, CM, "Q3"),
                        listed("SYS-V2", "View two", SMO_B, CM_OTHER, "Q3")),
                List.of());
    }

    /** The view entries about this System, read as the second VA POC so a reader never signs the first one out. */
    private JsonNode viewed(ApiClient reader, String system) {
        var response = reader.get("/api/audit-entries?size=200&action=SUBMISSION_VIEWED&system=" + system);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    private static long viewedBy(ApiClient reader, String actor) {
        var response = reader.get("/api/audit-entries?size=1&action=SUBMISSION_VIEWED&actor=" + actor);
        return ApiClient.json(response).get("total").asLong();
    }

    private static void assertIdsOnly(JsonNode entry) {
        String text = entry.toString().toLowerCase();
        assertThat(text).doesNotContain("view one").doesNotContain("sys-v").doesNotContain("@ci.annualva.test")
                .doesNotContain("sentinel");
        assertThat(entry.get("detail").propertyNames()).isEmpty();
    }

    @Test
    void aCyberManagersReadIsExactlyOneEntryAboutTheSystemAndTheVaSubmission() {
        registerSystems();
        String system = idOf("SYS-V1");
        String id = started(signedInAs(SMO_A), system).get("id").asString();
        ApiClient manager = signedInAs(CM);
        String actor = userId(manager);

        assertThat(read(manager, id).status()).isEqualTo(200);

        JsonNode entries = viewed(otherVaPoc(), system);
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(actor);
        assertThat(entry.get("actorRole").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(entry.get("systemId").asString()).isEqualTo(system);
        assertThat(entry.get("submissionId").asString()).isEqualTo(id);
        assertIdsOnly(entry);
    }

    @Test
    void aVaPocsReadIsExactlyOneEntryAndEachFurtherReadIsOneMore() {
        registerSystems();
        String system = idOf("SYS-V1");
        String id = started(signedInAs(SMO_A), system).get("id").asString();
        ApiClient poc = vaPoc();
        String actor = userId(poc);

        assertThat(read(poc, id).status()).isEqualTo(200);

        ApiClient reader = otherVaPoc();
        JsonNode entries = viewed(reader, system);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("actorId").asString()).isEqualTo(actor);
        assertThat(entries.get(0).get("actorRole").asString()).isEqualTo("VA_POC");
        assertIdsOnly(entries.get(0));
        assertThat(read(poc, id).status()).isEqualTo(200);
        assertThat(viewed(reader, system)).hasSize(2);
    }

    @Test
    void theOwningSmosReadWritesNoEntryAndNeitherDoesStartingIt() {
        registerSystems();
        String system = idOf("SYS-V1");
        ApiClient smo = signedInAs(SMO_A);
        String id = started(smo, system).get("id").asString();

        assertThat(read(smo, id).status()).isEqualTo(200);
        assertThat(read(smo, id).status()).isEqualTo(200);

        assertThat(viewed(otherVaPoc(), system)).isEmpty();
    }

    @Test
    void aVaPocWhoIsTheSmoOfTheSystemIsItsOwnerAndTheirReadIsNotRecorded() {
        registered(listed("SYS-V3", "View three", POC_ONE, CM, "Q3"));
        String system = idOf("SYS-V3");
        ApiClient poc = vaPoc();
        String id = started(poc, system).get("id").asString();

        assertThat(read(poc, id).status()).isEqualTo(200);

        assertThat(viewed(otherVaPoc(), system)).isEmpty();
    }

    @Test
    void aStrangersReadAMissingIdAndAnAuditorsReadAreNotRecordedByThisPath() {
        registerSystems();
        String system = idOf("SYS-V1");
        String id = started(signedInAs(SMO_A), system).get("id").asString();
        ApiClient stranger = signedInAs(CM_OTHER);
        String strangerId = userId(stranger);
        ApiClient auditor = signedInAs("sub.view.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "sub.view.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");
        String auditorId = userId(auditor);

        assertThat(read(stranger, id).status()).isEqualTo(404);
        assertThat(read(stranger, UUID.randomUUID().toString()).status()).isEqualTo(404);
        assertThat(read(auditor, id).status()).isEqualTo(403);

        ApiClient reader = otherVaPoc();
        assertThat(viewed(reader, system)).isEmpty();
        assertThat(viewedBy(reader, strangerId)).isZero();
        assertThat(viewedBy(reader, auditorId)).isZero();
    }

    @Test
    void nothingFromTheRequestOrTheSystemReachesTheEntryOrTheApplicationLog(CapturedOutput output) {
        registerSystems();
        String system = idOf("SYS-V1");
        String id = started(signedInAs(SMO_A), system).get("id").asString();
        ApiClient manager = signedInAs(CM);

        var response = manager.get(SUBMISSIONS + "/" + id + "?note=CVE-9999-SENTINEL");

        assertThat(response.status()).isEqualTo(200);
        JsonNode entries = viewed(otherVaPoc(), system);
        assertThat(entries).hasSize(1);
        assertIdsOnly(entries.get(0));
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999")
                .doesNotContain("view one").doesNotContain("sys-v1");
    }
}
