package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 24, IM8 lm-19 and as-13: errors never echo file content, and log lines carry only file ids, statuses and attempt
 * counts: never a file name, a quarantine or clean key, a scanner ticket or anything read from the file. A file that
 * carries a sentinel in its name and in its content goes through every stage and every way of being rejected, and the
 * sentinel must appear nowhere but the status the person asked for.
 */
@ExtendWith(OutputCaptureExtension.class)
class FileLoggingTest extends IngestionSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";

    private static byte[] withSentinel(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        return (text + "<!-- " + SENTINEL + " -->").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void logLinesCarryFileIdsAndStatusesAndNothingFromTheFileOrTheStores(CapturedOutput output) {
        Draft draft = draft();
        List<String> keys = new ArrayList<>();
        String accepted = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, SENTINEL + ".xml",
                withSentinel(Samples.dependencyCheckXml())).get("id").asString();
        keys.addAll(fileStore.keys(FileStore.Zone.QUARANTINE));
        String blocked = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, SENTINEL + "_quarantined.xml",
                withSentinel(Samples.dependencyCheckXml())).get("id").asString();
        String wrongType = uploaded(draft.smo(), draft.submissionId(), BURP_SUITE, SENTINEL + ".xml",
                withSentinel(Samples.dependencyCheckXml())).get("id").asString();
        String slow = uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, SENTINEL + "_never.xml",
                withSentinel(Samples.nessus())).get("id").asString();
        pass();
        keys.addAll(fileStore.keys(FileStore.Zone.QUARANTINE));
        pass();
        // A clean copy is kept under the file's own id, which the log is meant to carry; only quarantine keys are secret.

        List<String> bodies = new ArrayList<>();
        bodies.add(draft.smo().get(filesOf(draft.submissionId())).body());
        bodies.add(draft.smo().get(fileAt(draft.submissionId(), wrongType)).body());
        bodies.add(signedInAs(SMO_B).get(fileAt(draft.submissionId(), accepted) + "/content").body());
        bodies.add(draft.smo().get(fileAt(draft.submissionId(), blocked) + "/content").body());

        String log = output.getAll();
        assertThat(log).contains(accepted).contains(blocked).contains(wrongType).contains(slow)
                .contains("file-status").contains("SCHEMA_OK").contains("REJECTED_MALWARE")
                .contains("REJECTED_TYPE").contains("SCAN_SUBMITTED");
        assertThat(log).doesNotContain(SENTINEL);
        assertThat(log).as("no scanner ticket").doesNotContain("ticket-");
        assertThat(keys).isNotEmpty().allSatisfy(key -> assertThat(log).as("a store key").doesNotContain(key));
        assertThat(String.join("\n", bodies)).as("an error body or a status never echoes the file's content")
                .doesNotContain("payroll").doesNotContain("<analysis");
        assertThat(String.join("\n", bodies.subList(1, bodies.size()))).doesNotContain(SENTINEL + "_quarantined");
    }

    @Test
    void aRefusedUploadAndAScanThatCannotBeReachedLogNothingFromTheFileEither(CapturedOutput output) {
        Draft draft = draft();
        scanner.failNextSubmissions(3);
        String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, SENTINEL + ".xml",
                withSentinel(Samples.dependencyCheckXml())).get("id").asString();
        for (int n = 0; n < 3; n++) {
            pass();
            clock.advance(java.time.Duration.ofMinutes(3));
        }
        arrange.submissionState(draft.submissionId(), "IN_REVIEW");

        var refused = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, SENTINEL + ".xml",
                withSentinel(Samples.dependencyCheckXml()));

        JsonNode file = status(draft.smo(), draft.submissionId(), id);
        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL).contains(id).contains("REJECTED_SCAN_TIMEOUT");
    }

    @Test
    void aRejectionMessageIsTheSameSentenceWhateverTheFileHeld() {
        Draft draft = draft();

        JsonNode first = settled(draft, DEPENDENCY_CHECK, "one.html", withSentinel(Samples.html()));
        JsonNode second = settled(draft, DEPENDENCY_CHECK, "two.png", Samples.png());

        assertThat(first.get("message")).isEqualTo(second.get("message"));
        assertThat(ApiClient.json(draft.smo().get(filesOf(draft.submissionId()))).toString()).doesNotContain(SENTINEL);
    }
}
