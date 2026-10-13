package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.FakeMalwareScanner.Fate;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * IM8 as-12 and story 24 for the System Register workbook (ADR 0017): it goes through the same quarantine and malware
 * scan as a Scan Report file before {@code RegisterSheetReader} reads it. The scan is synchronous, because the file is
 * small and the VA POC waits for the preview: the upload still answers 201 with the preview when the file is clean, and
 * a blocked file, a scan that keeps timing out (three attempts, as for Scan Reports) or a scan result that does not match
 * the upload is refused with a fixed problem and never read. Only the scanner's clean copy is read, and the quarantined
 * copy never outlives the request.
 */
class RegisterScanTest extends RegisterSeam1 {

    private static final String PROBLEM = "https://annualva/problems/";

    private static byte[] workbook(String systemId) {
        return RegisterXlsx.register()
                .row(systemId, "Scanned", "scan.smo@ci.annualva.test", "scan.cm@ci.annualva.test", "Q1").build();
    }

    private static String userId(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session")).get("uuid").asString();
    }

    /** How many previews this VA POC has been audited as making. */
    private long previews(ApiClient poc) {
        var response = otherVaPoc().get("/api/audit-entries?size=1&action=REGISTER_PREVIEWED&actor=" + userId(poc));
        return ApiClient.json(response).get("total").asLong();
    }

    /** The refused-upload entries of the first VA POC, newest first, read as the second. */
    private JsonNode rejections(ApiClient poc) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=REGISTER_UPLOAD_REJECTED&actor="
                + userId(poc));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    @Test
    void aCleanWorkbookIsScannedAndStillAnswersWithThePreviewAndTheQuarantinedCopyIsGone() {
        ApiClient poc = vaPoc();

        var response = upload(poc, workbook("SYS-SC-1"));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(rowOf(ApiClient.json(response), "SYS-SC-1").get("outcome").asString()).isEqualTo("ADD");
        assertThat(scanner.submissions()).isEqualTo(1);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("the workbook is never stored").isZero();
    }

    @Test
    void aBlockedWorkbookIsRefusedWithAFixedProblemAndNeverBecomesAPreview() {
        ApiClient poc = vaPoc();
        long previewsBefore = previews(poc);
        scanner.nextFates(Fate.BLOCKED);

        var response = upload(poc, workbook("SYS-SC-SECRET"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "file-rejected-malware");
        assertThat(ApiClient.json(response).get("detail").asString()).contains("malware").contains("rejected");
        assertThat(response.body()).doesNotContain("SYS-SC-SECRET");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("malware is never retained").isZero();
        JsonNode rejected = rejections(poc).get(0);
        assertThat(rejected.get("detail").get("reject_code").asString()).isEqualTo("malware");
        assertThat(rejected.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(previews(poc)).as("no preview was made").isEqualTo(previewsBefore);
    }

    @Test
    void theScanComesBeforeTheFileIsReadSoABlockedFileThatIsNotAWorkbookIsMalware() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.BLOCKED);

        var response = upload(poc, "<html>not a workbook</html>".getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).isEqualTo(422);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "file-rejected-malware");
    }

    @Test
    void aFileThatIsNotAWorkbookIsStillRefusedAsOneAfterItIsScannedClean() {
        ApiClient poc = vaPoc();

        var response = upload(poc, "<html>not a workbook</html>".getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).isEqualTo(415);
        assertThat(scanner.submissions()).as("it was scanned first").isEqualTo(1);
    }

    @Test
    void anEmptyAndAnOversizedFileAreRefusedBeforeAnythingIsScanned() {
        ApiClient poc = vaPoc();

        assertThat(upload(poc, new byte[0]).status()).isEqualTo(400);
        assertThat(upload(poc, new byte[3 * 1024 * 1024]).status()).isEqualTo(413);

        assertThat(scanner.submissions()).isZero();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void aScanThatKeepsTimingOutIsTriedThreeTimesThenRefusedWithTheUploadAgainMessage() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.NEVER_FINISHES, Fate.NEVER_FINISHES, Fate.NEVER_FINISHES);

        var response = upload(poc, workbook("SYS-SC-2"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "scan-timeout");
        assertThat(ApiClient.json(response).get("detail").asString()).contains("rejected").contains("Upload the file again");
        assertThat(scanner.submissions()).as("the first attempt and two retries").isEqualTo(3);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(rejections(poc).get(0).get("detail").get("reject_code").asString()).isEqualTo("scan-timeout");
    }

    @Test
    void aScanThatTimesOutOnceAndAnswersCleanOnTheRetryGivesThePreview() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.NEVER_FINISHES, Fate.CLEAN);

        var response = upload(poc, workbook("SYS-SC-3"));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(scanner.submissions()).isEqualTo(2);
    }

    @Test
    void aScannerErrorIsRetriedLikeATimeout() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.ERROR, Fate.ERROR, Fate.CLEAN);

        var response = upload(poc, workbook("SYS-SC-4"));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(scanner.submissions()).isEqualTo(3);
    }

    @Test
    void aScannerThatCannotBeReachedOrAStoreThatCannotBeWrittenRefusesTheUploadLikeATimeout() {
        ApiClient poc = vaPoc();
        scanner.failNextSubmissions(3);

        var unreachable = upload(poc, workbook("SYS-SC-5"));
        fileStore.refuseWrites(true);
        var noStore = upload(poc, workbook("SYS-SC-5"));

        assertThat(unreachable.status()).isEqualTo(503);
        assertThat(problemType(unreachable)).isEqualTo(PROBLEM + "scan-timeout");
        assertThat(noStore.status()).isEqualTo(503);
        assertThat(problemType(noStore)).isEqualTo(PROBLEM + "scan-timeout");
    }

    @Test
    void aVerdictThatCannotBeAskedForIsWaitedOutAndThenGivenUpOn() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.CLEAN);
        scanner.failNextFetches(3);

        var response = upload(poc, workbook("SYS-SC-6"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "scan-timeout");
    }

    @Test
    void aVerdictOfUnchangedWithOtherBytesIsRefusedAsAMismatch() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.TAMPERED);

        var response = upload(poc, workbook("SYS-SC-7"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "scan-mismatch");
        assertThat(rejections(poc).get(0).get("detail").get("reject_code").asString()).isEqualTo("scan-mismatch");
    }

    @Test
    void aWorkbookTheScannerHadToChangeIsRefusedAndNeverReadNorKept() {
        ApiClient poc = vaPoc();
        long previewsBefore = previews(poc);
        scanner.nextFates(Fate.SANITIZED);

        var response = upload(poc, workbook("SYS-SC-SANITISED"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "file-modified-by-scan");
        assertThat(ApiClient.json(response).get("detail").asString()).contains("modify").contains("original");
        assertThat(response.body()).doesNotContain("SYS-SC-SANITISED");
        assertThat(rejections(poc).get(0).get("detail").get("reject_code").asString()).isEqualTo("sanitized");
        assertThat(previews(poc)).as("no preview was made").isEqualTo(previewsBefore);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
    }

    @Test
    void anAdapterThatThrowsAPlainExceptionIsRetriedLikeAnUnreachableScanner() {
        ApiClient poc = vaPoc();
        scanner.breakNextSubmissions(3);

        var response = upload(poc, workbook("SYS-SC-9"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "scan-timeout");
    }

    @Test
    void theRegisterStillChangesOnlyWhenAPreviewIsApplied() {
        ApiClient poc = vaPoc();
        scanner.nextFates(Fate.BLOCKED);
        upload(poc, workbook("SYS-SC-8"));

        assertThat(ApiClient.json(poc.get("/api/systems"))).isEmpty();
    }
}
