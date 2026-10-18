package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 22 and 84, IM8 as-1 and lm-19 (ticket 18, ADR 0026): supporting evidence is downloadable only once it is READY and
 * only by someone who may see the System (404 otherwise, the answer a missing file gets), as an attachment with the generic
 * content type that no browser renders, and every download is audited for every role. HTML is hostile active content: it
 * is never served in a way a browser would render it, and nothing from a file's bytes or name reaches a response body but
 * the download itself, a log line or an audit entry.
 */
@ExtendWith(OutputCaptureExtension.class)
class EvidenceDownloadTest extends IngestionSeam1 {

    private static final String SENTINEL = "CVE-9999-EVIDENCE-SENTINEL";
    private static final String ACTIVE_HTML = "<!DOCTYPE html><html><body><script>fetch('/api/csrf')</script>"
            + "<img src=x onerror=alert(1)>" + SENTINEL + "</body></html>";

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private String readyEvidence(Draft draft, String name, byte[] content) {
        JsonNode file = settledEvidence(draft, DEPENDENCY_CHECK, name, content);
        assertThat(file.get("status").asString()).isEqualTo("READY");
        return file.get("id").asString();
    }

    private static String contentOf(Draft draft, String id) {
        return fileAt(draft.submissionId(), id) + "/content";
    }

    private static void assertNotFound(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/not-found");
    }

    /** The entries of this action about this System, read as the second VA POC. */
    private JsonNode entries(String action, String system) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=" + action + "&system=" + system);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    @Test
    void aDownloadIsAnAttachmentWithTheGenericTypeNothingToSniffAndAPolicyThatAllowsNothing() {
        Draft draft = draft();
        String pdf = readyEvidence(draft, "report.pdf", Samples.pdf());
        String html = readyEvidence(draft, "report.html", text(ACTIVE_HTML));

        for (String id : List.of(pdf, html)) {
            ApiClient.Download download = draft.smo().download(contentOf(draft, id));

            assertThat(download.status()).isEqualTo(200);
            assertThat(download.header("Content-Disposition")).startsWith("attachment;").doesNotContain("inline");
            assertThat(download.header("Content-Type")).isEqualTo("application/octet-stream");
            assertThat(download.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(download.header("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
            assertThat(download.headers().get("content-security-policy")).as("one policy, not two").hasSize(1);
            assertThat(download.headers().get("x-content-type-options")).hasSize(1);
            assertThat(download.header("Cache-Control")).contains("no-store");
        }
        assertThat(draft.smo().download(contentOf(draft, pdf)).body()).isEqualTo(Samples.pdf());
        assertThat(draft.smo().download(contentOf(draft, html)).body()).isEqualTo(text(ACTIVE_HTML));
    }

    @Test
    void askingForHtmlOrAnyOtherTypeChangesNothingTheFileIsStillAnOctetStreamAttachment() {
        Draft draft = draft();
        String html = readyEvidence(draft, "report.html", text(ACTIVE_HTML));

        for (String accept : List.of("text/html", "text/html,application/xhtml+xml,*/*;q=0.8", "application/pdf", "*/*")) {
            ApiClient.Response response = draft.smo().get(contentOf(draft, html), Map.of("Accept", accept));

            assertThat(response.status()).as(accept).isEqualTo(200);
            assertThat(response.header("Content-Type")).as(accept).isEqualTo("application/octet-stream");
            assertThat(response.header("Content-Disposition")).as(accept).startsWith("attachment;");
        }
    }

    @Test
    void theDownloadNameIsTheCleanedNameAndCanNeitherSmuggleAHeaderNorMarkup() {
        Draft draft = draft();
        String id = readyEvidence(draft, "..\\..\\<img src=x onerror=alert(1)>;x\u202E.html", text(ACTIVE_HTML));

        ApiClient.Download download = draft.smo().download(contentOf(draft, id));

        String disposition = download.header("Content-Disposition");
        assertThat(disposition).startsWith("attachment;").contains("_img src_x onerror_alert(1)__x_.html")
                .doesNotContain("<").doesNotContain(">").doesNotContain("..");
        assertThat(status(draft.smo(), draft.submissionId(), id).get("name").asString())
                .isEqualTo("_img src_x onerror_alert(1)__x_.html");
    }

    @Test
    void theExtensionOfTheDownloadComesFromTheDetectedFormatNeverFromTheNameTheSmoGave() {
        Draft draft = draft();
        Map<String, String[]> offered = new java.util.LinkedHashMap<>();
        // uploaded name, content kind, expected download name
        offered.put("payroll.hta", new String[] {"html", "payroll.html"});
        offered.put("payroll.xhtml", new String[] {"html", "payroll.html"});
        offered.put("payroll.SVG", new String[] {"html", "payroll.html"});
        offered.put("payroll.wsf", new String[] {"html", "payroll.html"});
        offered.put("payroll.pdf", new String[] {"html", "payroll.html"});
        offered.put("payroll.htm", new String[] {"html", "payroll.html"});
        offered.put("payroll.HTML", new String[] {"html", "payroll.html"});
        offered.put("payroll", new String[] {"html", "payroll.html"});
        offered.put("payroll.", new String[] {"html", "payroll.html"});
        offered.put("scan.v2.hta", new String[] {"html", "scan.v2.html"});
        offered.put("scan.pdf.hta", new String[] {"html", "scan.pdf.html"});
        offered.put("scan.hta.", new String[] {"html", "scan.hta.html"});
        offered.put(".hta", new String[] {"html", "file.html"});
        offered.put("payroll .hta", new String[] {"html", "payroll.html"});
        offered.put("payroll.exe", new String[] {"pdf", "payroll.pdf"});
        offered.put("payroll.html", new String[] {"pdf", "payroll.pdf"});
        offered.put("payroll.PDF", new String[] {"pdf", "payroll.pdf"});
        offered.put("payroll.pdf.lnk", new String[] {"pdf", "payroll.pdf.pdf"});

        offered.forEach((name, expected) -> {
            byte[] content = expected[0].equals("pdf") ? Samples.pdf() : text(ACTIVE_HTML);
            String id = readyEvidence(draft, name, content);
            ApiClient.Download download = draft.smo().download(contentOf(draft, id));

            assertThat(download.status()).as(name).isEqualTo(200);
            assertThat(download.header("Content-Disposition")).as(name).contains("filename=\"" + expected[1] + "\"");
            assertThat(download.body()).as(name).isEqualTo(content);
        });
    }

    @Test
    void theDownloadExtensionFollowsTheBytesForEveryRoleAndTheStoredNameIsLeftAsTheCleanedUpload() {
        Draft draft = draft();
        String id = readyEvidence(draft, "payroll.hta", text(ACTIVE_HTML));

        assertThat(signedInAs(CM).download(contentOf(draft, id)).header("Content-Disposition"))
                .contains("filename=\"payroll.html\"");
        assertThat(vaPoc().download(contentOf(draft, id)).header("Content-Disposition"))
                .contains("filename=\"payroll.html\"");
        assertThat(status(draft.smo(), draft.submissionId(), id).get("name").asString()).isEqualTo("payroll.hta");
    }

    @Test
    void aMachineReadableFilesDownloadKeepsItsNameAsBefore() {
        Draft draft = draft();
        String id = settled(draft, DEPENDENCY_CHECK, "scan.xml", Samples.dependencyCheckXml()).get("id").asString();

        assertThat(draft.smo().download(contentOf(draft, id)).header("Content-Disposition"))
                .contains("filename=\"scan.xml\"");
    }

    @Test
    void nothingFromTheFileIsInAnyResponseButTheDownloadItself() {
        Draft draft = draft();
        String id = readyEvidence(draft, SENTINEL + ".html", text(ACTIVE_HTML));
        String rejected = settledEvidence(draft, DEPENDENCY_CHECK, "x.html", text("<script>" + SENTINEL))
                .get("id").asString();
        List<String> bodies = new ArrayList<>();

        bodies.add(draft.smo().get(filesOf(draft.submissionId())).body());
        bodies.add(draft.smo().get(fileAt(draft.submissionId(), id)).body());
        bodies.add(draft.smo().get(fileAt(draft.submissionId(), rejected)).body());
        bodies.add(draft.smo().get(fileAt(draft.submissionId(), id) + "/metadata").body());
        bodies.add(draft.smo().get(fileAt(draft.submissionId(), rejected) + "/content").body());
        bodies.add(signedInAs(SMO_B).get(contentOf(draft, id)).body());
        bodies.add(draft.smo().get(SUBMISSIONS + "/" + draft.submissionId() + "/findings").body());
        bodies.add(draft.smo().get(SUBMISSIONS + "/" + draft.submissionId()).body());

        for (String body : bodies) {
            assertThat(body).doesNotContain("<script").doesNotContain("onerror").doesNotContain("fetch(");
            assertThat(body.replace(SENTINEL + ".html", "")).as("only the person's own file name, as data")
                    .doesNotContain(SENTINEL);
        }
        for (var response : List.of(draft.smo().get(filesOf(draft.submissionId())),
                draft.smo().get(fileAt(draft.submissionId(), id)))) {
            assertThat(response.header("Content-Type")).startsWith("application/json").doesNotContain("html");
        }
    }

    @Test
    void evidenceIsOnlyServedOnceItIsReadyAndNeverWhenItWasRejected() {
        Draft draft = draft();
        String waiting = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "w.pdf", Samples.pdf())
                .get("id").asString();
        assertNotFound(draft.smo().get(contentOf(draft, waiting)));
        pass();
        assertNotFound(draft.smo().get(contentOf(draft, waiting)));
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
        pass();
        assertThat(draft.smo().download(contentOf(draft, waiting)).status()).as("READY now").isEqualTo(200);

        String wrongType = settledEvidence(draft, DEPENDENCY_CHECK, "x.pdf", Samples.png()).get("id").asString();
        String malware = settledEvidence(draft, DEPENDENCY_CHECK, "x_quarantined.pdf", Samples.pdf()).get("id").asString();
        String sanitized = settledEvidence(draft, DEPENDENCY_CHECK, "x_sanitized.pdf", Samples.pdf()).get("id").asString();
        for (String id : List.of(wrongType, malware, sanitized)) {
            assertNotFound(draft.smo().get(contentOf(draft, id)));
            assertNotFound(vaPoc().get(contentOf(draft, id)));
        }
    }

    @Test
    void theCyberManagerAndTheVaPocMayDownloadAndEveryoneElseIsToldNotFoundAsForAMissingFile() {
        Draft draft = draft();
        String id = readyEvidence(draft, "report.pdf", Samples.pdf());
        String missing = "00000000-0000-4000-8000-000000000000";
        ApiClient.Response absent = signedInAs(SMO_B).get(fileAt(missing, missing) + "/content");

        assertThat(signedInAs(CM).download(contentOf(draft, id)).status()).isEqualTo(200);
        assertThat(vaPoc().download(contentOf(draft, id)).status()).isEqualTo(200);
        for (ApiClient stranger : List.of(signedInAs(SMO_B), signedInAs(CM_OTHER))) {
            ApiClient.Response refused = stranger.get(contentOf(draft, id));
            assertNotFound(refused);
            assertThat(ApiClient.json(refused).get("detail")).isEqualTo(ApiClient.json(absent).get("detail"));
            assertNotFound(stranger.get(fileAt(draft.submissionId(), id)));
        }
        assertThat(api.get(contentOf(draft, id)).status()).as("not signed in").isEqualTo(401);
    }

    @Test
    void everyDownloadOfEvidenceIsAuditedForEveryRoleTheOwnerIncludedAndANotFoundIsNot() {
        Draft draft = draft();
        String id = readyEvidence(draft, "payroll-prod-scan.pdf", Samples.pdf());
        ApiClient manager = signedInAs(CM);

        assertThat(draft.smo().download(contentOf(draft, id)).status()).isEqualTo(200);
        assertThat(manager.download(contentOf(draft, id)).status()).isEqualTo(200);
        assertThat(signedInAs(SMO_B).download(contentOf(draft, id)).status()).isEqualTo(404);
        status(manager, draft.submissionId(), id);
        status(draft.smo(), draft.submissionId(), id);

        JsonNode downloads = entries("FILE_DOWNLOADED", draft.systemId());
        assertThat(downloads).hasSize(2);
        assertThat(downloads.get(0).get("actorId").asString()).isEqualTo(userId(manager));
        assertThat(downloads.get(1).get("actorId").asString()).isEqualTo(userId(draft.smo()));
        for (JsonNode entry : downloads) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
            assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(id);
            assertThat(entry.get("detail").propertyNames()).containsExactly("file_id");
        }
        JsonNode views = entries("FILE_VIEWED", draft.systemId());
        assertThat(views).as("the manager's status read, not the owner's").hasSize(1);
        assertThat(views.get(0).get("actorId").asString()).isEqualTo(userId(manager));
    }

    @Test
    void anUploadAndARejectionOfEvidenceAreAuditedWithIdsAndFixedCodesOnly() {
        Draft draft = draft();
        String ready = readyEvidence(draft, "payroll-prod-scan.pdf", Samples.pdf());
        String rejected = settledEvidence(draft, DEPENDENCY_CHECK, "payroll-prod-scan.html", text("<p>" + SENTINEL))
                .get("id").asString();

        JsonNode uploads = entries("FILE_UPLOADED", draft.systemId());
        JsonNode rejections = entries("FILE_REJECTED", draft.systemId());

        assertThat(uploads).hasSize(2);
        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).get("detail").get("file_id").asString()).isEqualTo(rejected);
        assertThat(rejections.get(0).get("detail").get("reject_code").asString()).isEqualTo("type");
        assertThat(rejections.get(0).get("actorId").isNull()).isTrue();
        assertThat(uploads.get(1).get("detail").get("file_id").asString()).isEqualTo(ready);
        for (JsonNode entry : List.of(uploads.get(0), uploads.get(1), rejections.get(0))) {
            assertThat(entry.toString().toLowerCase()).doesNotContain("payroll").doesNotContain(SENTINEL.toLowerCase())
                    .doesNotContain("sys-f1").doesNotContain("<p>");
        }
    }

    @Test
    void theRejectionOfAnEncryptedPdfIsAuditedWithItsFixedCodeAndNothingOfTheFile(CapturedOutput output) {
        Draft draft = draft();
        byte[] locked = text("%PDF-1.7\n" + SENTINEL + "\ntrailer\n<< /Encrypt 5 0 R >>\n%%EOF\n");
        String id = settledEvidence(draft, DEPENDENCY_CHECK, SENTINEL + ".pdf", locked).get("id").asString();

        JsonNode rejections = entries("FILE_REJECTED", draft.systemId());

        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).get("detail").get("file_id").asString()).isEqualTo(id);
        assertThat(rejections.get(0).get("detail").get("reject_code").asString()).isEqualTo("encrypted");
        assertThat(rejections.get(0).toString()).doesNotContain(SENTINEL).doesNotContain("Encrypt");
        assertThat(output.getAll()).contains(id).contains("REJECTED_ENCRYPTED").doesNotContain(SENTINEL)
                .doesNotContain("/Encrypt");
    }

    @Test
    void noLogLineEchoesAnEvidenceFilesNameOrBytesWhateverBecameOfIt(CapturedOutput output) {
        Draft draft = draft();
        List<String> keys = new ArrayList<>();
        String ready = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, SENTINEL + ".pdf",
                text("%PDF-1.7 " + SENTINEL)).get("id").asString();
        keys.addAll(fileStore.keys(FileStore.Zone.QUARANTINE));
        String html = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, SENTINEL + ".html",
                text(ACTIVE_HTML)).get("id").asString();
        String wrongType = uploadedEvidence(draft.smo(), draft.submissionId(), BURP_SUITE, SENTINEL + ".pdf",
                text(SENTINEL)).get("id").asString();
        String blocked = uploadedEvidence(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION,
                SENTINEL + "_quarantined.html", text(ACTIVE_HTML)).get("id").asString();
        String tooLarge = uploadedEvidence(draft.smo(), draft.submissionId(), BURP_SUITE, SENTINEL + "_big.html",
                Samples.paddedTo(text(ACTIVE_HTML), 10 * 1024 * 1024 + 1)).get("id").asString();
        pass();
        keys.addAll(fileStore.keys(FileStore.Zone.QUARANTINE));
        pass();
        assertThat(draft.smo().download(contentOf(draft, ready)).status()).isEqualTo(200);
        assertThat(draft.smo().download(contentOf(draft, html)).status()).isEqualTo(200);
        assertNotFound(draft.smo().get(contentOf(draft, wrongType)));

        String log = output.getAll();
        assertThat(log).contains(ready).contains(html).contains(wrongType).contains(blocked).contains(tooLarge)
                .contains("READY").contains("REJECTED_TYPE").contains("REJECTED_MALWARE")
                .contains("REJECTED_TOO_LARGE");
        assertThat(log).doesNotContain(SENTINEL).doesNotContain("<script").doesNotContain("onerror")
                .doesNotContain("fetch(");
        assertThat(keys).isNotEmpty().allSatisfy(key -> assertThat(log).as("a store key").doesNotContain(key));
    }

    @Test
    void theScreensDataCarriesNoPreviewOfAFileOnlyItsNameStatusAndSize() {
        Draft draft = draft();
        String id = readyEvidence(draft, "report.html", text(ACTIVE_HTML));

        JsonNode file = status(draft.smo(), draft.submissionId(), id);

        assertThat(file.propertyNames()).containsExactlyInAnyOrder("id", "submissionId", "reportType", "kind", "name",
                "sizeBytes", "uploadSha256", "status", "rejectCode", "message", "scanAttempts", "detectedFormat",
                "uploadedAt", "replacesId", "supersededBy", "stages");
        assertThat(file.toString()).doesNotContain(SENTINEL).doesNotContain("<script");
    }
}
