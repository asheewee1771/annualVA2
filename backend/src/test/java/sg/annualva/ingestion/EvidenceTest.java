package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 22 (ticket 18, ADR 0026): a PDF or HTML report attached to a Scan Report as supporting evidence goes through the
 * same quarantine, malware scan and type check as every file, skips the schema check and the parse, and rests in READY;
 * anything else is rejected. The type comes from the leading bytes alone, never from the name or the declared type, and a
 * file that looks like two things resolves the same way every time (a PDF marker at the very start wins, then HTML). Real
 * PDF and HTML files from the tools are not exercised until ticket 58.
 */
class EvidenceTest extends IngestionSeam1 {

    private static final List<String> STAGES_READY = List.of("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK",
            "READY");

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private void assertReady(JsonNode file, String format) {
        assertThat(file.get("status").asString()).as(file.toString()).isEqualTo("READY");
        assertThat(file.get("kind").asString()).isEqualTo(EVIDENCE);
        assertThat(file.get("detectedFormat").asString()).isEqualTo(format);
        assertThat(file.get("rejectCode").isNull()).isTrue();
        assertThat(file.get("message").isNull()).isTrue();
        assertThat(stages(file)).as("no schema check and no parse").containsExactlyElementsOf(STAGES_READY);
    }

    private void assertRejected(JsonNode file, String code) {
        assertThat(file.get("status").asString()).as(file.toString()).isEqualTo("REJECTED_" + code);
        assertThat(file.get("rejectCode").asString()).isEqualTo(code);
        assertThat(file.get("message").asString()).contains("so the upload was rejected.");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "REJECTED_" + code);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("a rejected file keeps no bytes").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("a rejected file keeps no bytes").isZero();
    }

    @Test
    void aPdfAndAnHtmlReportRunQuarantineScanAndTypeThenRestInReadyWithoutASchemaCheckOrAParse() {
        Draft draft = draft();
        String pdf = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "report.pdf", Samples.pdf())
                .get("id").asString();
        String html = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "report.html",
                Samples.html()).get("id").asString();
        assertThat(status(draft.smo(), draft.submissionId(), pdf).get("status").asString()).isEqualTo("QUARANTINED");

        pass();
        assertThat(status(draft.smo(), draft.submissionId(), pdf).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        pass();

        assertReady(status(draft.smo(), draft.submissionId(), pdf), "PDF");
        assertReady(status(draft.smo(), draft.submissionId(), html), "HTML");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the unscanned copies are gone").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isEqualTo(2);
        assertThat(ApiClient.json(draft.smo().get(findingsFor(draft.submissionId()))).get("total").asInt())
                .as("nothing is read from evidence, so it makes no Finding").isZero();
        assertThat(draft.smo().get(fileAt(draft.submissionId(), pdf) + "/metadata").status())
                .as("evidence names no scan date or target").isEqualTo(404);
    }

    private static String findingsFor(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/findings?size=1";
    }

    @Test
    void evidenceIsAcceptedForEveryScanReportAndNeverReadSoABrokenDocumentIsStillKept() {
        Draft draft = draft();
        byte[] unfinishedHtml = text("<html><body><p>no end <b>");

        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "a.pdf", Samples.pdf()), "PDF");
        assertReady(settledEvidence(draft, SECURE_CONFIGURATION, "b.html", unfinishedHtml), "HTML");
        assertReady(settledEvidence(draft, BURP_SUITE, "c.html", Samples.html()), "HTML");
        assertThat(ApiClient.json(draft.smo().get(findingsFor(draft.submissionId()))).get("total").asInt()).isZero();
    }

    @Test
    void theTypeComesFromTheBytesAloneNeverFromTheNameOrTheDeclaredContentType() {
        Draft draft = draft();
        var asPdf = draft.smo().postMultipart(filesOf(draft.submissionId()),
                Map.of("reportType", DEPENDENCY_CHECK, "kind", EVIDENCE), "file", "innocent.pdf", "application/pdf",
                Samples.png());
        var asHtml = draft.smo().postMultipart(filesOf(draft.submissionId()),
                Map.of("reportType", DEPENDENCY_CHECK, "kind", EVIDENCE), "file", "innocent.html", "text/html",
                Samples.png());
        pass();
        pass();

        assertThat(asPdf.status()).isEqualTo(202);
        assertThat(asHtml.status()).isEqualTo(202);
        assertRejectedOnly(draft, ApiClient.json(asPdf).get("id").asString(), "TYPE");
        assertRejectedOnly(draft, ApiClient.json(asHtml).get("id").asString(), "TYPE");
        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "report.xml", Samples.pdf()), "PDF");
        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "report", Samples.html()), "HTML");
        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "report.exe", Samples.pdf()), "PDF");
    }

    private void assertRejectedOnly(Draft draft, String id, String code) {
        JsonNode file = status(draft.smo(), draft.submissionId(), id);
        assertThat(file.get("status").asString()).isEqualTo("REJECTED_" + code);
        assertThat(file.get("rejectCode").asString()).isEqualTo(code);
    }

    @Test
    void aPdfIsOnlyAPdfWhenItsMarkerIsTheVeryFirstBytes() {
        Draft draft = draft();
        Map<String, byte[]> notPdf = new LinkedHashMap<>();
        notPdf.put("a space first", text(" %PDF-1.7\n"));
        notPdf.put("a newline first", text("\n%PDF-1.7\n"));
        notPdf.put("a byte order mark first", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Samples.pdf()));
        notPdf.put("junk before the marker", text("GARBAGE %PDF-1.7\n"));
        notPdf.put("no hyphen", text("%PDF1.7\n"));
        notPdf.put("lower case", text("%pdf-1.7\n"));
        notPdf.put("the marker cut short", text("%PDF"));
        notPdf.put("just a percent", text("%"));

        notPdf.forEach((name, content) -> assertRejected(settledEvidence(draft, DEPENDENCY_CHECK, "x.pdf", content), "TYPE"));
        assertThat(notPdf).hasSize(8);
    }

    @Test
    void anHtmlDocumentIsRecognisedByAConservativeSniffOfItsFirstBytes() {
        Draft draft = draft();
        String commentBeforeTheLimit = "<!--" + "c".repeat(3000) + "-->\n<html>";
        Map<String, byte[]> html = new LinkedHashMap<>();
        html.put("the HTML5 doctype", text("<!DOCTYPE html><html><body>x</body></html>"));
        html.put("lower case", text("<!doctype html>\n<html>"));
        html.put("upper case, with an attribute", text("<HTML LANG=\"en\"><BODY>x"));
        html.put("a legacy doctype", text("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\">\n<html>"));
        html.put("an element with no content", text("<html/>"));
        html.put("a byte order mark and whitespace", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                text(" \r\n\t\f<!DOCTYPE html>")));
        html.put("comments first", text("<!-- one --> <!-- two -->\n<!DOCTYPE html>"));
        html.put("a comment inside the window", text(commentBeforeTheLimit));
        html.put("an XML declaration first", text("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>"));
        html.put("a declaration then a comment", text("<?xml version=\"1.0\"?><!-- x --><html xmlns=\"x\">"));

        html.forEach((name, content) -> {
            JsonNode file = settledEvidence(draft, DEPENDENCY_CHECK, "x.html", content);
            assertThat(file.get("status").asString()).as(name).isEqualTo("READY");
            assertThat(file.get("detectedFormat").asString()).as(name).isEqualTo("HTML");
        });
        assertThat(html).hasSize(10);
    }

    @Test
    void aFileThatDoesNotShowItsDoctypeOrHtmlTagUpFrontIsNotHtml() {
        Draft draft = draft();
        String commentPastTheWindow = "<!--" + "c".repeat(4096) + "-->\n<html>";
        Map<String, byte[]> notHtml = new LinkedHashMap<>();
        notHtml.put("a script and nothing else", text("<script>alert(1)</script>"));
        notHtml.put("a body with no html tag", text("<body><p>hello</p></body>"));
        notHtml.put("an html-like name", text("<htmlx>"));
        notHtml.put("a longer doctype name", text("<!DOCTYPE htmlx>"));
        notHtml.put("another doctype", text("<!DOCTYPE svg><svg/>"));
        notHtml.put("a doctype with nothing after it", text("<!DOCTYPE html"));
        notHtml.put("the tag cut short", text("<html"));
        notHtml.put("text before the tag", text("hello <html>"));
        notHtml.put("an unterminated comment", text("<!-- never ends <html>"));
        notHtml.put("a comment past the window", text(commentPastTheWindow));
        notHtml.put("a processing instruction that is not an XML declaration", text("<?php echo 1; ?><html>"));
        notHtml.put("an XML declaration that never ends", text("<?xml version=\"1.0\"\n<html>"));
        notHtml.put("a UTF-16 document", "<html>".getBytes(StandardCharsets.UTF_16));
        notHtml.put("an SVG image", text("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>1</script></svg>"));
        notHtml.put("plain text", text("just some notes"));
        notHtml.put("a JSON document", text("{\"html\":\"<html>\"}"));
        notHtml.put("a PNG", Samples.png());
        notHtml.put("whitespace only", text("  \n "));

        notHtml.forEach((name, content) -> {
            JsonNode file = settledEvidence(draft, DEPENDENCY_CHECK, "x.html", content);
            assertThat(file.get("status").asString()).as(name).isEqualTo("REJECTED_TYPE");
        });
        assertThat(notHtml).hasSize(18);
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("a rejected file keeps no bytes").isZero();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    /** A PDF-looking file of window plus 1,000 bytes whose last bytes, {@code fromTheEnd} of them, start with the token. */
    private static byte[] pdfWithTheTokenAt(String token, int fromTheEnd) {
        byte[] file = new byte[EvidenceFormat.ENCRYPT_WINDOW + 1000];
        java.util.Arrays.fill(file, (byte) ' ');
        byte[] marker = text("%PDF-1.7\n");
        System.arraycopy(marker, 0, file, 0, marker.length);
        byte[] bytes = text(token);
        System.arraycopy(bytes, 0, file, file.length - fromTheEnd, bytes.length);
        return file;
    }

    @Test
    void aPdfWhoseTrailerNamesAnEncryptionDictionaryIsRejectedWithAPlainMessageAndKeepsNoBytes() {
        Draft draft = draft();

        JsonNode file = settledEvidence(draft, DEPENDENCY_CHECK, "locked.pdf", Samples.encryptedPdf());

        assertRejected(file, "ENCRYPTED");
        assertThat(file.get("message").asString()).contains("password").contains("without a password");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("PDF");
        assertThat(draft.smo().download(fileAt(draft.submissionId(), file.get("id").asString()) + "/content").status())
                .as("never served").isEqualTo(404);
    }

    @Test
    void anEncryptedPdfIsFoundWhicheverWayItsTrailerWritesTheEntryAndWhateverTheFileIsCalled() {
        Draft draft = draft();
        Map<String, byte[]> encrypted = new LinkedHashMap<>();
        encrypted.put("a reference", pdfWithTheTokenAt("<< /Encrypt 5 0 R >>", 40));
        encrypted.put("an inline dictionary", pdfWithTheTokenAt("<</Root 1 0 R/Encrypt<</Filter/Standard>>>>", 60));
        encrypted.put("a carriage return after it", pdfWithTheTokenAt("/Encrypt\r5 0 R", 20));
        encrypted.put("a tab after it", pdfWithTheTokenAt("/Encrypt\t5 0 R", 20));
        encrypted.put("an array after it", pdfWithTheTokenAt("/Encrypt[5 0 R]", 20));
        encrypted.put("the end of the file after it", pdfWithTheTokenAt("/Encrypt", 8));
        encrypted.put("the first byte of the window that is looked at", pdfWithTheTokenAt("/Encrypt ", EvidenceFormat.ENCRYPT_WINDOW));

        encrypted.forEach((how, content) -> {
            assertThat(settledEvidence(draft, DEPENDENCY_CHECK, "report.html", content).get("status").asString())
                    .as(how).isEqualTo("REJECTED_ENCRYPTED");
        });
        assertThat(encrypted).hasSize(7);
    }

    @Test
    void aPdfThatOnlyMentionsSomethingLikeEncryptOutsideTheTrailerOrAsAnotherNameIsKept() {
        Draft draft = draft();
        Map<String, byte[]> kept = new LinkedHashMap<>();
        kept.put("another name that starts the same", pdfWithTheTokenAt("/EncryptMetadata false", 30));
        kept.put("a longer name", pdfWithTheTokenAt("/Encrypted 1", 30));
        kept.put("an escaped longer name", pdfWithTheTokenAt("/Encrypt#41 1", 30));
        kept.put("a lower case name", pdfWithTheTokenAt("/encrypt 5 0 R", 30));
        kept.put("no slash", pdfWithTheTokenAt("Encrypt 5 0 R", 30));
        kept.put("before the tail that is looked at", pdfWithTheTokenAt("/Encrypt ", EvidenceFormat.ENCRYPT_WINDOW + 1));
        kept.put("at the start of the file", concat(text("%PDF-1.7 /Encrypt 5 0 R\n"), new byte[100_000]));

        kept.forEach((why, content) -> assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "kept.pdf", content), "PDF"));
        assertThat(kept).hasSize(7);
    }

    @Test
    void theEncryptionCheckIsForPdfsOnlyAndAnHtmlReportThatSaysEncryptIsKept() {
        Draft draft = draft();
        byte[] html = text("<!DOCTYPE html><html><body>/Encrypt 5 0 R</body></html>");

        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "about-pdf.html", html), "HTML");
    }

    @Test
    void aMachineReadableReportAttachedAsEvidenceIsRejectedWhateverItIsCalled() {
        Draft draft = draft();
        Map<String, byte[]> reports = new LinkedHashMap<>();
        reports.put("Dependency-Check XML", Samples.dependencyCheckXml());
        reports.put("Dependency-Check JSON", Samples.dependencyCheckJson());
        reports.put("XCCDF", Samples.xccdfResults());
        reports.put("ARF", Samples.arf());
        reports.put("Nessus", Samples.nessus());
        reports.put("Burp", Samples.burp());

        reports.forEach((name, content) -> assertRejected(
                settledEvidence(draft, DEPENDENCY_CHECK, "report.html", content), "TYPE"));
    }

    @Test
    void aPdfOrHtmlUploadedAsAMachineReadableFileIsRejectedForEveryScanReport() {
        Draft draft = draft();
        for (String reportType : List.of(DEPENDENCY_CHECK, SECURE_CONFIGURATION, BURP_SUITE)) {
            assertRejected(settled(draft, reportType, "report.pdf", Samples.pdf()), "TYPE");
            assertRejected(settled(draft, reportType, "report.html", Samples.html()), "TYPE");
        }
    }

    @Test
    void aFileThatIsBothResolvesTheSameWayEveryTimeAPdfMarkerAtTheStartWinsThenHtml() {
        Draft draft = draft();
        byte[] pdfThatIsAlsoHtml = text("%PDF-1.4\n<!DOCTYPE html><html><body><script>1</script></body></html>");
        byte[] htmlWithAPdfHeaderInside = text("<!DOCTYPE html><html><body>\n%PDF-1.4\n</body></html>");
        byte[] htmlWhoseCommentIsAPdfHeader = text("<!-- %PDF-1.4 -->\n<html><body>x</body></html>");
        byte[] htmlWithAPdfHeaderAtItsStart = text("<html>%PDF-1.4");
        byte[] pdfWithTrailingHtml = concat(Samples.pdf(), text("</html><html>"));
        byte[] markerAfterABom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Samples.pdf());

        for (int again = 0; again < 2; again++) {
            assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "a.html", pdfThatIsAlsoHtml), "PDF");
            assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "b.pdf", htmlWithAPdfHeaderInside), "HTML");
            assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "c.pdf", htmlWhoseCommentIsAPdfHeader), "HTML");
            assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "d.pdf", htmlWithAPdfHeaderAtItsStart), "HTML");
            assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "e.html", pdfWithTrailingHtml), "PDF");
        }
        assertThat(settledEvidence(draft, DEPENDENCY_CHECK, "f.pdf", markerAfterABom).get("status").asString())
                .as("neither at the very start nor an HTML document").isEqualTo("REJECTED_TYPE");
    }

    @Test
    void aPdfHasItsOwnSizeLimitOfTwentyMegabytesAndAnHtmlReportTenAndAFileOverItIsRejectedBeforeItIsRead() {
        Draft draft = draft();
        long mb = 1024L * 1024L;
        byte[] pdfAtTheLimit = pdfOfSize((int) (20 * mb));
        byte[] pdfOverTheLimit = pdfOfSize((int) (20 * mb) + 1);
        byte[] pdfOverTheHtmlLimit = pdfOfSize((int) (10 * mb) + 1);
        byte[] htmlAtTheLimit = Samples.paddedTo(Samples.html(), (int) (10 * mb));
        byte[] htmlOverTheLimit = Samples.paddedTo(Samples.html(), (int) (10 * mb) + 1);

        assertThat(htmlAtTheLimit).hasSize((int) (10 * mb));
        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "at.html", htmlAtTheLimit), "HTML");
        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "over-html-limit.pdf", pdfOverTheHtmlLimit), "PDF");
        assertReady(settledEvidence(draft, DEPENDENCY_CHECK, "at.pdf", pdfAtTheLimit), "PDF");
        JsonNode bigHtml = settledEvidence(draft, DEPENDENCY_CHECK, "over.html", htmlOverTheLimit);
        JsonNode bigPdf = settledEvidence(draft, DEPENDENCY_CHECK, "over.pdf", pdfOverTheLimit);

        for (JsonNode file : List.of(bigHtml, bigPdf)) {
            assertThat(file.get("status").asString()).isEqualTo("REJECTED_TOO_LARGE");
            assertThat(file.get("rejectCode").asString()).isEqualTo("TOO_LARGE");
            assertThat(file.get("message").asString()).contains("larger than the limit");
            assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN",
                    "REJECTED_TOO_LARGE");
        }
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("only the three accepted files keep bytes").isEqualTo(3);
    }

    private static byte[] pdfOfSize(int bytes) {
        byte[] pdf = new byte[bytes];
        byte[] start = Samples.pdf();
        System.arraycopy(start, 0, pdf, 0, start.length);
        return pdf;
    }

    @Test
    void theMalwareScanComesFirstAndAppliesToEvidenceAsToEveryFile() {
        Draft draft = draft();

        JsonNode blocked = settledEvidence(draft, DEPENDENCY_CHECK, "report_quarantined.pdf", Samples.pdf());
        JsonNode blockedAndWrongType = settledEvidence(draft, DEPENDENCY_CHECK, "x_quarantined.png", Samples.png());
        JsonNode sanitized = settledEvidence(draft, DEPENDENCY_CHECK, "report_sanitized.html", Samples.html());
        JsonNode tampered = settledEvidence(draft, DEPENDENCY_CHECK, "report_tampered.pdf", Samples.pdf());

        assertThat(blocked.get("status").asString()).isEqualTo("REJECTED_MALWARE");
        assertThat(blockedAndWrongType.get("status").asString()).as("scanned before it is type-checked")
                .isEqualTo("REJECTED_MALWARE");
        assertThat(sanitized.get("status").asString()).as("a changed file is not accepted")
                .isEqualTo("REJECTED_SANITIZED");
        assertThat(sanitized.get("message").asString()).contains("original file again");
        assertThat(tampered.get("status").asString()).isEqualTo("REJECTED_HASH_MISMATCH");
        for (JsonNode file : List.of(blocked, blockedAndWrongType, sanitized, tampered)) {
            assertThat(stages(file)).doesNotContain("SCAN_CLEAN", "TYPE_OK", "READY");
            var download = draft.smo().download(fileAt(draft.submissionId(), file.get("id").asString()) + "/content");
            assertThat(download.status()).as("no download").isEqualTo(404);
        }
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("malware and the rest are never retained").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
    }

    @Test
    void aFileStillWithTheScannerIsNotTypeCheckedAndOneThatNeverFinishesIsRejectedAfterThreeAttempts() {
        Draft draft = draft();
        String id = uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "slow_never.png",
                Samples.png()).get("id").asString();
        pass();
        pass();

        JsonNode waiting = status(draft.smo(), draft.submissionId(), id);
        assertThat(stages(waiting)).containsExactly("QUARANTINED", "SCAN_SUBMITTED");

        for (int attempt = 0; attempt < 3; attempt++) {
            clock.advance(java.time.Duration.ofMinutes(3));
            pass();
        }
        JsonNode file = status(draft.smo(), draft.submissionId(), id);
        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
        assertThat(file.get("scanAttempts").asInt()).isEqualTo(3);
    }

    @Test
    void evidenceHasItsOwnTwentyFileLimitPerScanReportAndTheMachineReadableFilesKeepTheirs() {
        Draft draft = draft();
        for (int n = 0; n < 20; n++) {
            uploadedEvidence(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "e" + n + ".pdf", Samples.pdf());
        }

        var evidenceFull = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, EVIDENCE, "more.pdf",
                Samples.pdf());
        var otherReport = upload(draft.smo(), draft.submissionId(), BURP_SUITE, EVIDENCE, "burp.pdf", Samples.pdf());
        var report = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, MACHINE_READABLE, "dc.xml",
                Samples.dependencyCheckXml());

        assertThat(evidenceFull.status()).isEqualTo(409);
        assertThat(problemType(evidenceFull)).isEqualTo("https://annualva/problems/too-many-files");
        assertThat(otherReport.status()).as("each Scan Report has its own limit").isEqualTo(202);
        assertThat(report.status()).as("the report files are counted apart from the evidence").isEqualTo(202);
    }

    @Test
    void onlyTheSystemsOwnSmoAttachesEvidenceAndOnlyWhileTheVaSubmissionTakesFiles() {
        Draft draft = draft();

        var manager = upload(signedInAs(CM), draft.submissionId(), DEPENDENCY_CHECK, EVIDENCE, "a.pdf", Samples.pdf());
        var poc = upload(vaPoc(), draft.submissionId(), DEPENDENCY_CHECK, EVIDENCE, "a.pdf", Samples.pdf());
        var otherSmo = upload(signedInAs(SMO_B), draft.submissionId(), DEPENDENCY_CHECK, EVIDENCE, "a.pdf",
                Samples.pdf());
        var empty = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, EVIDENCE, "a.pdf", new byte[0]);
        arrange.submissionState(draft.submissionId(), "IN_REVIEW");
        var inReview = upload(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, EVIDENCE, "a.pdf", Samples.pdf());

        assertThat(manager.status()).isEqualTo(403);
        assertThat(poc.status()).isEqualTo(403);
        assertThat(otherSmo.status()).isEqualTo(404);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(inReview.status()).isEqualTo(409);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("nothing was kept").isZero();
    }
}
