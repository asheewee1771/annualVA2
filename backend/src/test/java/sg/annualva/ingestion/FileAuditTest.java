package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 84 and IM8 lm-4 for Scan Report files: an upload, a rejection, a read of a file's status by someone who is not the
 * System's own SMO/Tech Lead, and every download are audit entries that hold ids and fixed codes only, never a file name,
 * a System ID or anything read from the file (lm-19). A request that is answered 404 is not recorded by this path.
 */
class FileAuditTest extends IngestionSeam1 {

    private static final String SECRET_NAME = "payroll-prod-scan";

    /** The entries of this action about this System, read as the second VA POC. */
    private JsonNode entries(String action, String system) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=" + action + "&system=" + system);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    private static void assertIdsOnly(JsonNode entry) {
        String text = entry.toString().toLowerCase();
        assertThat(text).doesNotContain(SECRET_NAME).doesNotContain("sys-f1").doesNotContain("file one")
                .doesNotContain("throwaway").doesNotContain("@ci.annualva.test");
    }

    @Test
    void anUploadIsOneEntryAboutTheSystemTheVaSubmissionAndTheFile() {
        Draft draft = draft();

        String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, SECRET_NAME + ".xml",
                Samples.dependencyCheckXml()).get("id").asString();

        JsonNode entries = entries("FILE_UPLOADED", draft.systemId());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(draft.smo()));
        assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(entry.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(id);
        assertThat(entry.get("detail").propertyNames()).containsExactly("file_id");
        assertIdsOnly(entry);
    }

    @Test
    void aRefusedUploadIsAnEntryWithTheOutcomeRefusedAndAFixedReason() {
        Draft draft = draft();
        arrange.submissionState(draft.submissionId(), "IN_REVIEW");

        var response = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE,
                SECRET_NAME + ".xml", Samples.dependencyCheckXml());

        assertThat(response.status()).isEqualTo(409);
        JsonNode entries = entries("FILE_UPLOADED", draft.systemId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("state-not-editable");
        assertThat(entries.get(0).get("detail").propertyNames()).containsExactly("reason");
        assertIdsOnly(entries.get(0));
    }

    @Test
    void aFullScanReportRefusalIsAnEntryToo() {
        Draft draft = draft();
        for (int n = 0; n < 20; n++) {
            uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "f" + n + ".xml",
                    Samples.dependencyCheckXml());
        }

        upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "more.xml",
                Samples.dependencyCheckXml());

        JsonNode entries = entries("FILE_UPLOADED", draft.systemId());
        assertThat(entries).hasSize(21);
        assertThat(entries.get(0).get("outcome").asString()).as("newest first").isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("too-many-files");
    }

    @Test
    void aRejectionIsAnEntryWithTheRejectCodeAndNoActor() {
        Draft draft = draft();

        JsonNode file = settled(draft, DEPENDENCY_CHECK, SECRET_NAME + "_quarantined.xml",
                Samples.dependencyCheckXml());

        JsonNode entries = entries("FILE_REJECTED", draft.systemId());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entry.get("actorId").isNull()).as("the scan job acts for no one").isTrue();
        assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(file.get("id").asString());
        assertThat(entry.get("detail").get("reject_code").asString()).isEqualTo("malware");
        assertIdsOnly(entry);
    }

    @Test
    void everyKindOfRejectionRecordsItsOwnFixedCode() {
        Draft draft = draft();
        settled(draft, DEPENDENCY_CHECK, "a.html", Samples.html());
        settled(draft, DEPENDENCY_CHECK, "b.xml", "<analysis><scanInfo>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        settled(draft, DEPENDENCY_CHECK, "c_tampered.xml", Samples.dependencyCheckXml());

        JsonNode entries = entries("FILE_REJECTED", draft.systemId());

        assertThat(entries).hasSize(3);
        assertThat(java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .map(entry -> entry.get("detail").get("reject_code").asString()).toList())
                .containsExactlyInAnyOrder("type", "unreadable", "hash-mismatch");
    }

    @Test
    void aReadOfStatusByACyberManagerOrAVaPocIsAnEntryAndTheOwnersIsNot() {
        Draft draft = draft();
        String id = settled(draft, DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckXml()).get("id").asString();
        ApiClient manager = signedInAs(CM);
        ApiClient poc = vaPoc();

        status(manager, draft.submissionId(), id);
        draft.smo().get(filesOf(draft.submissionId()));
        status(draft.smo(), draft.submissionId(), id);
        poc.get(filesOf(draft.submissionId()));

        JsonNode entries = entries("FILE_VIEWED", draft.systemId());
        assertThat(entries).as("the owner's reads, which carry no report content, write nothing").hasSize(2);
        JsonNode list = entries.get(0);
        JsonNode single = entries.get(1);
        assertThat(list.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(list.get("detail").get("count").asInt()).isEqualTo(1);
        assertThat(list.get("detail").propertyNames()).containsExactly("count");
        assertThat(single.get("actorRole").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(single.get("actorId").asString()).isEqualTo(userId(manager));
        assertThat(single.get("detail").get("file_id").asString()).isEqualTo(id);
        assertIdsOnly(list);
        assertIdsOnly(single);
    }

    @Test
    void everyDownloadIsAnEntryWhoeverMakesIt() {
        Draft draft = draft();
        String id = settled(draft, DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckXml()).get("id").asString();
        String content = fileAt(draft.submissionId(), id) + "/content";
        ApiClient manager = signedInAs(CM);

        assertThat(draft.smo().download(content).status()).isEqualTo(200);
        assertThat(manager.download(content).status()).isEqualTo(200);

        JsonNode entries = entries("FILE_DOWNLOADED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("actorId").asString()).isEqualTo(userId(manager));
        assertThat(entries.get(1).get("actorId").asString()).isEqualTo(userId(draft.smo()));
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
            assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(id);
            assertIdsOnly(entry);
        }
    }

    @Test
    void aDownloadAnsweredWithNotFoundIsNotARecordOfADownload() {
        Draft draft = draft();
        String id = settled(draft, DEPENDENCY_CHECK, "dc.xml", Samples.dependencyCheckXml()).get("id").asString();
        String rejected = settled(draft, DEPENDENCY_CHECK, "page.html", Samples.html()).get("id").asString();

        assertThat(signedInAs(SMO_B).download(fileAt(draft.submissionId(), id) + "/content").status()).isEqualTo(404);
        assertThat(draft.smo().download(fileAt(draft.submissionId(), rejected) + "/content").status()).isEqualTo(404);

        assertThat(entries("FILE_DOWNLOADED", draft.systemId())).isEmpty();
        assertThat(entries("FILE_VIEWED", draft.systemId())).isEmpty();
    }
}
