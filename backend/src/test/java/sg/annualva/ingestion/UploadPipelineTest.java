package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 20 (the pipeline) and story 24: an upload is quarantined and answered at once, then the stages run in the fixed
 * order quarantine, malware scan, type allowlist, schema check, and a file that fails a stage never reaches the next. The
 * order is proved by what a file ends up as when it would fail more than one stage, and by the stages a file went through.
 */
class UploadPipelineTest extends IngestionSeam1 {

    @Test
    void anUploadIsQuarantinedAndAnsweredWith202AndItsStatus() {
        Draft draft = draft();

        var response = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "dc.xml",
                Samples.dependencyCheckXml());

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        JsonNode file = sg.annualva.harness.ApiClient.json(response);
        assertThat(response.header("Location")).isEqualTo(fileAt(draft.submissionId(), file.get("id").asString()));
        assertThat(response.header("Cache-Control")).contains("no-store");
        assertThat(file.get("status").asString()).isEqualTo("QUARANTINED");
        assertThat(file.get("reportType").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(file.get("kind").asString()).isEqualTo(MACHINE_READABLE);
        assertThat(file.get("name").asString()).isEqualTo("dc.xml");
        assertThat(file.get("sizeBytes").asLong()).isEqualTo(Samples.dependencyCheckXml().length);
        assertThat(file.get("uploadSha256").asString()).as("the SHA-256 of the bytes as received")
                .isEqualTo(sha256Of(Samples.dependencyCheckXml()));
        assertThat(file.get("rejectCode").isNull()).isTrue();
        assertThat(file.get("scanAttempts").asInt()).isZero();
        assertThat(stages(file)).containsExactly("QUARANTINED");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the bytes wait in quarantine").isEqualTo(1);
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
        assertThat(scanner.submissions()).as("nothing is scanned until the job runs").isZero();
    }

    @Test
    void aCleanFileRunsQuarantineThenScanThenTypeThenSchemaInThatOrder() {
        Draft draft = draft();
        String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "dc.xml",
                Samples.dependencyCheckXml()).get("id").asString();

        pass();
        JsonNode submitted = status(draft.smo(), draft.submissionId(), id);
        assertThat(submitted.get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        assertThat(submitted.get("scanAttempts").asInt()).isEqualTo(1);

        pass();
        JsonNode file = status(draft.smo(), draft.submissionId(), id);

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK", "PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("DEPENDENCY_CHECK_XML");
        assertThat(file.get("rejectCode").isNull()).isTrue();
        assertThat(file.get("message").isNull()).isTrue();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the unscanned copy is gone").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isEqualTo(1);
    }

    @Test
    void theScanComesBeforeTheTypeCheckSoMalwareInTheWrongTypeIsMalware() {
        Draft draft = draft();

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "page_quarantined.html", Samples.html());

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_MALWARE");
        assertThat(file.get("rejectCode").asString()).isEqualTo("MALWARE");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "REJECTED_MALWARE");
    }

    @Test
    void theTypeCheckComesBeforeTheSchemaCheckSoTheWrongTypeIsNotCalledMalformed() {
        Draft draft = draft();
        byte[] truncatedHtml = "<html><body><p>no end".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "page.html", truncatedHtml);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_TYPE");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "REJECTED_TYPE");
    }

    @Test
    void nothingIsTypeCheckedWhileTheScanIsStillOut() {
        Draft draft = draft();

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "slow_never.png", Samples.png());

        assertThat(file.get("status").asString()).as("a file that would fail the type check waits for its scan")
                .isEqualTo("SCAN_SUBMITTED");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED");
    }

    @Test
    void aFileTheScannerHadToChangeIsRejectedWithTheOriginalAgainMessageAndKeepsNoBytes() {
        Draft draft = draft();

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "dc_sanitized.xml", Samples.dependencyCheckXml());

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SANITIZED");
        assertThat(file.get("rejectCode").asString()).isEqualTo("SANITIZED");
        assertThat(file.get("message").asString()).contains("modify").contains("original file again");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "REJECTED_SANITIZED");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("the altered copy is never kept").isZero();
        var download = draft.smo().download(fileAt(draft.submissionId(), file.get("id").asString()) + "/content");
        assertThat(download.status()).as("no download").isEqualTo(404);
    }

    private static String sha256Of(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void severalFilesOfOneScanReportAreAllKeptAndReadBack() {
        Draft draft = draft();
        String first = uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, "a.nessus", Samples.nessus())
                .get("id").asString();
        String second = uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, "b.xml", Samples.xccdfResults())
                .get("id").asString();
        pass();
        pass();

        var response = draft.smo().get(filesOf(draft.submissionId()));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode list = sg.annualva.harness.ApiClient.json(response);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asString()).as("oldest first").isEqualTo(first);
        assertThat(list.get(1).get("id").asString()).isEqualTo(second);
        assertThat(List.of(list.get(0).get("status").asString(), list.get(1).get("status").asString()))
                .containsOnly("SCHEMA_OK");
        assertThat(stages(list.get(0))).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK",
                "SCHEMA_OK");
    }
}
