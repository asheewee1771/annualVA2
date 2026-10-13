package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 78 and IM8 ac-1, as-7: only the System's own SMO/Tech Lead uploads; anyone who may see the System reads a file's
 * status; a file's bytes are served as an attachment only when it is clean and only to someone who may see the System,
 * otherwise 404, and a direct download by someone who may not is refused with the same 404 a missing file gets (ADR 0013).
 * Uploads are accepted only while the VA Submission is a Draft or Returned for Rework.
 */
class FileAccessTest extends IngestionSeam1 {

    private String cleanFile(Draft draft) {
        JsonNode file = settled(draft, DEPENDENCY_CHECK, "report.xml", Samples.dependencyCheckXml());
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        return file.get("id").asString();
    }

    private static void assertNotFound(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/not-found");
    }

    @Test
    void aCleanFileIsServedAsAnAttachmentWithTheGenericTypeToItsSmo() {
        Draft draft = draft();
        String id = cleanFile(draft);

        ApiClient.Download download = draft.smo().download(fileAt(draft.submissionId(), id) + "/content");

        assertThat(download.status()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(Samples.dependencyCheckXml());
        assertThat(download.header("Content-Disposition")).startsWith("attachment").contains("report.xml");
        assertThat(download.header("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(download.header("Cache-Control")).contains("no-store");
        assertThat(download.header("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void theCyberManagerOfTheSystemAndAVaPocMayReadAndDownloadButAnotherCyberManagerAndAnotherSmoCannot() {
        Draft draft = draft();
        String id = cleanFile(draft);
        String content = fileAt(draft.submissionId(), id) + "/content";
        ApiClient manager = signedInAs(CM);
        ApiClient poc = vaPoc();
        ApiClient otherManager = signedInAs(CM_OTHER);
        ApiClient otherSmo = signedInAs(SMO_B);

        assertThat(status(manager, draft.submissionId(), id).get("status").asString()).isEqualTo("PARSED");
        assertThat(manager.download(content).status()).isEqualTo(200);
        assertThat(status(poc, draft.submissionId(), id).get("status").asString()).isEqualTo("PARSED");
        assertThat(poc.download(content).status()).isEqualTo(200);
        for (ApiClient stranger : List.of(otherManager, otherSmo)) {
            assertNotFound(stranger.get(fileAt(draft.submissionId(), id)));
            assertNotFound(stranger.get(filesOf(draft.submissionId())));
            ApiClient.Download refused = stranger.download(content);
            assertThat(refused.status()).as("a direct download by someone who may not see the System").isEqualTo(404);
            assertThat(new String(refused.body(), StandardCharsets.UTF_8)).doesNotContain("throwaway");
        }
    }

    @Test
    void aDirectDownloadIsTheSameAnswerForAFileSomeoneMayNotSeeAndOneThatDoesNotExist() {
        Draft draft = draft();
        String id = cleanFile(draft);
        ApiClient stranger = signedInAs(SMO_B);
        String missing = "00000000-0000-4000-8000-000000000000";

        ApiClient.Response refused = stranger.get(fileAt(draft.submissionId(), id) + "/content");
        ApiClient.Response absent = stranger.get(fileAt(missing, missing) + "/content");

        assertNotFound(refused);
        assertNotFound(absent);
        assertThat(ApiClient.json(refused).propertyNames()).containsExactlyInAnyOrderElementsOf(
                ApiClient.json(absent).propertyNames());
        assertThat(ApiClient.json(refused).get("detail")).isEqualTo(ApiClient.json(absent).get("detail"));
    }

    @Test
    void aFileThatIsNotCleanYetCannotBeViewedByAnyone() {
        Draft draft = draft();
        String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "dc.xml",
                Samples.dependencyCheckXml()).get("id").asString();
        String content = fileAt(draft.submissionId(), id) + "/content";

        assertNotFound(draft.smo().get(content));
        assertNotFound(vaPoc().get(content));
        pass();
        assertNotFound(draft.smo().get(content));
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
        pass();
        assertThat(draft.smo().download(content).status()).as("clean now").isEqualTo(200);
    }

    @Test
    void aRejectedFileIsNeverServed() {
        Draft draft = draft();
        String id = settled(draft, DEPENDENCY_CHECK, "page.html", Samples.html()).get("id").asString();

        assertNotFound(draft.smo().get(fileAt(draft.submissionId(), id) + "/content"));
        assertNotFound(vaPoc().get(fileAt(draft.submissionId(), id) + "/content"));
    }

    @Test
    void aFileIsOnlyFoundUnderTheVaSubmissionItBelongsTo() {
        Draft draft = draft();
        String id = cleanFile(draft);
        ApiClient otherSmo = signedInAs(SMO_B);
        String otherSubmission = started(otherSmo, idOf("SYS-F2")).get("id").asString();

        assertNotFound(otherSmo.get(fileAt(otherSubmission, id)));
        assertNotFound(otherSmo.get(fileAt(otherSubmission, id) + "/content"));
        assertThat(ApiClient.json(otherSmo.get(filesOf(otherSubmission)))).isEmpty();
    }

    @Test
    void onlyTheSystemsOwnSmoCanUpload() {
        Draft draft = draft();
        byte[] dc = Samples.dependencyCheckXml();

        var manager = upload(signedInAs(CM), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "a.xml", dc);
        var poc = upload(vaPoc(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "a.xml", dc);
        var otherSmo = upload(signedInAs(SMO_B), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "a.xml", dc);
        var nobody = upload(anotherBrowser().withCsrf(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE,
                "a.xml", dc);

        assertThat(manager.status()).isEqualTo(403);
        assertThat(poc.status()).isEqualTo(403);
        assertNotFound(otherSmo);
        assertThat(nobody.status()).isEqualTo(401);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("nothing of theirs was kept").isZero();
        assertThat(ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))).isEmpty();
    }

    @Test
    void anUploadToAVaSubmissionThatDoesNotExistIsNotFound() {
        Draft draft = draft();

        var response = upload(draft.smo(), "00000000-0000-4000-8000-000000000000", DEPENDENCY_CHECK,
                MACHINE_READABLE, "a.xml", Samples.dependencyCheckXml());

        assertNotFound(response);
    }

    @Test
    void filesAreTakenInDraftAndReturnedForReworkAndNotWhileInReviewOrAfter() {
        Draft draft = draft();
        byte[] dc = Samples.dependencyCheckXml();

        assertThat(upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "a.xml", dc).status())
                .as("Draft").isEqualTo(202);
        arrange.submissionState(draft.submissionId(), "RETURNED_FOR_REWORK");
        assertThat(upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "b.xml", dc).status())
                .as("Returned for Rework").isEqualTo(202);
        for (String state : List.of("IN_REVIEW", "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED",
                "WITHDRAWN", "NOT_ENDORSED")) {
            arrange.submissionState(draft.submissionId(), state);
            var refused = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "c.xml", dc);
            assertThat(refused.status()).as(state).isEqualTo(409);
            assertThat(problemType(refused)).isEqualTo("https://annualva/problems/invalid-transition");
        }
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("only the two accepted uploads").isEqualTo(2);
    }

    @Test
    void aScanReportHoldsAtMostTwentyFilesAndARejectedOneDoesNotCountAgainstIt() {
        Draft draft = draft();
        byte[] dc = Samples.dependencyCheckXml();
        for (int n = 0; n < 20; n++) {
            assertThat(upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "f" + n + ".xml",
                    dc).status()).isEqualTo(202);
        }

        var full = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "one-more.xml", dc);
        var other = upload(draft.smo(), draft.submissionId(), BURP_SUITE, MACHINE_READABLE, "burp.xml", Samples.burp());

        assertThat(full.status()).isEqualTo(409);
        assertThat(problemType(full)).isEqualTo("https://annualva/problems/too-many-files");
        assertThat(other.status()).as("each Scan Report has its own limit").isEqualTo(202);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isEqualTo(21);
    }

    @Test
    void aRejectedFileFreesItsPlaceInTheScanReport() {
        Draft draft = draft();
        for (int n = 0; n < 20; n++) {
            uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "f" + n + "_quarantined.xml",
                    Samples.dependencyCheckXml());
        }
        pass();
        pass();
        pass();

        var again = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "again.xml",
                Samples.dependencyCheckXml());

        assertThat(again.status()).as("all twenty were blocked and no longer count").isEqualTo(202);
    }

    @Test
    void anEmptyFileAndBadParametersAreRefusedBeforeAnythingIsKept() {
        Draft draft = draft();
        byte[] dc = Samples.dependencyCheckXml();

        var empty = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "a.xml", new byte[0]);
        var badType = upload(draft.smo(), draft.submissionId(), "NOT_A_REPORT", MACHINE_READABLE, "a.xml", dc);
        var badKind = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "BOTH", "a.xml", dc);
        var noKind = draft.smo().postMultipart(filesOf(draft.submissionId()),
                java.util.Map.of("reportType", DEPENDENCY_CHECK), "file", "a.xml", "application/xml", dc);
        var noFile = draft.smo().postMultipart(filesOf(draft.submissionId()),
                java.util.Map.of("reportType", DEPENDENCY_CHECK, "kind", MACHINE_READABLE), "other", "a.xml",
                "application/xml", dc);

        assertThat(empty.status()).isEqualTo(400);
        assertThat(problemType(empty)).isEqualTo("https://annualva/problems/file-empty");
        assertThat(badType.status()).isEqualTo(400);
        assertThat(badType.body()).doesNotContain("NOT_A_REPORT");
        assertThat(badKind.status()).isEqualTo(400);
        assertThat(noKind.status()).isEqualTo(400);
        assertThat(noFile.status()).isEqualTo(400);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void aStoreThatCannotBeWrittenRefusesTheUploadWithoutKeepingARecord() {
        Draft draft = draft();
        fileStore.refuseWrites(true);

        var response = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "a.xml",
                Samples.dependencyCheckXml());

        assertThat(response.status()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo("https://annualva/problems/storage-unavailable");
        assertThat(ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))).isEmpty();
    }

    @Test
    void aCleanFileThatTheStoreCannotReadBackAnswers503() {
        Draft draft = draft();
        String id = cleanFile(draft);
        fileStore.refuseReads(true);

        var response = draft.smo().get(fileAt(draft.submissionId(), id) + "/content");

        assertThat(response.status()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo("https://annualva/problems/storage-unavailable");
    }

    @Test
    void theNameKeepsOnlyLettersDigitsAndAFewMarksAndAnythingElseBecomesAnUnderscore() {
        Draft draft = draft();
        String[][] cases = {
            {"report (final)-v2_1.xml", "report (final)-v2_1.xml"},
            {"<img onerror=alert(1)>.xml", "_img onerror_alert(1)_.xml"},
            {"a;b&c=d%e'f\"g.xml", "a_b_c_d_e_f_g.xml"},
            {"tab\there.xml", "tab_here.xml"},
        };
        for (String[] each : cases) {
            String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, each[0],
                    Samples.dependencyCheckXml()).get("id").asString();
            assertThat(status(draft.smo(), draft.submissionId(), id).get("name").asString()).as(each[0])
                    .isEqualTo(each[1]);
        }
    }

    @Test
    void aFileOverTheContainerCeilingIsRefusedWith413AndNothingIsKept() {
        Draft draft = draft();

        var response = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "huge.xml",
                new byte[51 * 1024 * 1024]);

        assertThat(response.status()).as(response.body()).isEqualTo(413);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))).isEmpty();
    }

    @Test
    void theNameIsCleanedOfAnyPathAndControlCharactersAndShortened() {
        Draft draft = draft();
        String hostile = "..\\..\\secret\\evil\u0007name\u202E.xml";
        String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, hostile,
                Samples.dependencyCheckXml()).get("id").asString();
        String longName = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "n".repeat(300) + ".xml",
                Samples.dependencyCheckXml()).get("id").asString();
        String nothing = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "dir/",
                Samples.dependencyCheckXml()).get("id").asString();

        assertThat(status(draft.smo(), draft.submissionId(), id).get("name").asString()).isEqualTo("evil_name_.xml");
        assertThat(status(draft.smo(), draft.submissionId(), longName).get("name").asString()).hasSize(100);
        assertThat(status(draft.smo(), draft.submissionId(), nothing).get("name").asString()).isEqualTo("file");
    }
}
