package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 24 and 27: scanning goes through the scanner boundary (submit, check, fetch clean). A blocked verdict rejects the
 * file and discards the quarantined bytes; a scan that times out is submitted again, and after the third attempt the file
 * is rejected with the message that the upload was rejected and should be uploaded again; at most ten files are with the
 * scanner at once. The attempt timeout is read from the controllable clock.
 */
class ScanVerdictTest extends IngestionSeam1 {

    private static final Duration PAST_THE_TIMEOUT = Duration.ofMinutes(3);

    private String uploadedId(Draft draft, String name) {
        return uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, name, Samples.dependencyCheckXml())
                .get("id").asString();
    }

    private JsonNode file(Draft draft, String id) {
        return status(draft.smo(), draft.submissionId(), id);
    }

    @Test
    void aBlockedVerdictRejectsTheFileAndDiscardsTheQuarantinedBytes() {
        Draft draft = draft();

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "report_quarantined.xml", Samples.dependencyCheckXml());

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_MALWARE");
        assertThat(file.get("rejectCode").asString()).isEqualTo("MALWARE");
        assertThat(file.get("message").asString()).contains("malware").contains("rejected");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("malware is never retained").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
    }

    @Test
    void aScanThatKeepsTimingOutIsSubmittedThreeTimesAndThenRejectedWithTheUploadAgainMessage() {
        Draft draft = draft();
        String id = uploadedId(draft, "slow_never.xml");

        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).isEqualTo(1);
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).as("the first retry").isEqualTo(2);
        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).as("the second retry").isEqualTo(3);
        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        clock.advance(PAST_THE_TIMEOUT);
        pass();

        JsonNode rejected = file(draft, id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
        assertThat(rejected.get("rejectCode").asString()).isEqualTo("SCAN_TIMEOUT");
        assertThat(rejected.get("message").asString()).contains("rejected").contains("Upload the file again");
        assertThat(rejected.get("scanAttempts").asInt()).isEqualTo(3);
        assertThat(stages(rejected)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_SUBMITTED",
                "SCAN_SUBMITTED", "REJECTED_SCAN_TIMEOUT");
        assertThat(scanner.submissions()).as("the first attempt and two retries").isEqualTo(3);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void anAttemptThatIsNotYetDueIsLeftToFinish() {
        Draft draft = draft();
        String id = uploadedId(draft, "slow_never.xml");
        pass();

        clock.advance(Duration.ofMinutes(1));
        pass();

        assertThat(file(draft, id).get("scanAttempts").asInt()).isEqualTo(1);
        assertThat(scanner.submissions()).isEqualTo(1);
    }

    @Test
    void aRetryThatTheScannerAnswersCleanIsAccepted() {
        Draft draft = draft();
        scanner.stallNext(1);
        String id = uploadedId(draft, "dc.xml");
        pass();
        clock.advance(PAST_THE_TIMEOUT);

        pass();
        pass();

        JsonNode accepted = file(draft, id);
        assertThat(accepted.get("status").asString()).isEqualTo("SCHEMA_OK");
        assertThat(accepted.get("scanAttempts").asInt()).isEqualTo(2);
    }

    @Test
    void aScannerErrorIsRetriedLikeATimeoutAndGivenUpOnAfterThree() {
        Draft draft = draft();
        String id = uploadedId(draft, "broken_error.xml");

        pass();
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).isEqualTo(2);
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).isEqualTo(3);
        pass();

        assertThat(file(draft, id).get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
    }

    @Test
    void aScannerThatCannotBeReachedCountsEverySubmissionAsAnAttemptAndIsNotTriedAgainBeforeTheTimeout() {
        Draft draft = draft();
        scanner.failNextSubmissions(3);
        String id = uploadedId(draft, "dc.xml");

        pass();
        JsonNode first = file(draft, id);
        assertThat(first.get("status").asString()).as("not yet with the scanner").isEqualTo("QUARANTINED");
        assertThat(first.get("scanAttempts").asInt()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).as("an outage is not tried again at once").isEqualTo(1);
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).isEqualTo(2);
        clock.advance(PAST_THE_TIMEOUT);
        pass();

        JsonNode rejected = file(draft, id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
        assertThat(rejected.get("scanAttempts").asInt()).isEqualTo(3);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void aRetryThatCannotBeSubmittedCountsAndWaitsTheTimeoutBeforeTheNextOne() {
        Draft draft = draft();
        String id = uploadedId(draft, "slow_never.xml");
        pass();
        clock.advance(PAST_THE_TIMEOUT);
        scanner.failNextSubmissions(1);

        pass();
        JsonNode failed = file(draft, id);
        assertThat(failed.get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        assertThat(failed.get("scanAttempts").asInt()).as("the failed submission counted").isEqualTo(2);

        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).as("paced like a failed check").isEqualTo(2);
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        assertThat(file(draft, id).get("scanAttempts").asInt()).isEqualTo(3);
        assertThat(scanner.submissions()).isEqualTo(2);
    }

    @Test
    void anAdapterThatThrowsAPlainExceptionOnSubmitIsRejectedAfterThreeAttemptsAndNeverStarvesTheOthers() {
        Draft draft = draft();
        scanner.breakNextSubmissions(3);
        String poison = uploadedId(draft, "poison.xml");

        pass();
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        clock.advance(PAST_THE_TIMEOUT);
        pass();

        JsonNode rejected = file(draft, poison);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
        assertThat(rejected.get("scanAttempts").asInt()).isEqualTo(3);
        String next = uploadedId(draft, "next.xml");
        pass();
        pass();
        assertThat(file(draft, next).get("status").asString()).as("the pipeline is not held up").isEqualTo("SCHEMA_OK");
    }

    @Test
    void anAdapterThatThrowsAPlainExceptionOnEveryCheckIsRejectedAfterItsAttemptsRunOut() {
        Draft draft = draft();
        String id = uploadedId(draft, "dc.xml");
        pass();
        scanner.breakChecks(true);

        for (int n = 0; n < 3; n++) {
            clock.advance(PAST_THE_TIMEOUT);
            pass();
        }

        JsonNode rejected = file(draft, id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED_SCAN_TIMEOUT");
        assertThat(rejected.get("scanAttempts").asInt()).isEqualTo(3);
    }

    @Test
    void bytesAreDeletedOnlyAfterTheOutcomeIsRecordedAndAFailedDeleteIsRetriedWithTheKeyKept() {
        Draft draft = draft();
        fileStore.refuseDeletes(true);

        JsonNode blocked = settled(draft, DEPENDENCY_CHECK, "report_quarantined.xml", Samples.dependencyCheckXml());
        JsonNode accepted = settled(draft, DEPENDENCY_CHECK, "report.xml", Samples.dependencyCheckXml());

        assertThat(blocked.get("status").asString()).isEqualTo("REJECTED_MALWARE");
        assertThat(accepted.get("status").asString()).isEqualTo("SCHEMA_OK");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the delete failed, so both blobs are still held")
                .isEqualTo(2);

        pass();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("still failing").isEqualTo(2);
        fileStore.refuseDeletes(false);
        pass();

        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the next pass found them by their kept keys").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isEqualTo(1);
    }

    @Test
    void aRejectionThatCannotBeWrittenLeavesTheFileAndItsBytesAsTheyWereAndIsRejectedForTheRightReasonNextPass() {
        Draft draft = draft();
        String id = uploadedId(draft, "report_quarantined.xml");
        pass();
        arrange.reportFileFault("REJECTED");
        try {
            pass();

            JsonNode stuck = file(draft, id);
            assertThat(stuck.get("status").asString()).as("the write rolled back").isEqualTo("SCAN_SUBMITTED");
            assertThat(stuck.get("scanAttempts").asInt()).as("no attempt was burned").isEqualTo(1);
            assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("nothing was deleted").isEqualTo(1);
        } finally {
            arrange.reportFileFault("off");
        }

        pass();

        JsonNode rejected = file(draft, id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED_MALWARE");
        assertThat(rejected.get("scanAttempts").asInt()).isEqualTo(1);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void anOutcomeThatCannotBeWrittenLeavesNoCleanCopyBehind() {
        Draft draft = draft();
        String id = uploadedId(draft, "dc.xml");
        pass();
        arrange.reportFileFault("SCHEMA_OK");
        try {
            pass();

            assertThat(file(draft, id).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
            assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("the copy stored for it was removed again").isZero();
            assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the original is still held").isEqualTo(1);
        } finally {
            arrange.reportFileFault("off");
        }

        pass();

        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCHEMA_OK");
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isEqualTo(1);
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void aPassThatLosesTheRaceToAPassThatAcceptedTheFileLeavesTheWinnersCopyAndDownloadsWork() {
        Draft draft = draft();
        String id = uploadedId(draft, "dc.xml");
        pass();
        scanner.beforeNextFetch(arrange::scan);

        pass();

        JsonNode file = file(draft, id);
        assertThat(file.get("status").asString()).isEqualTo("SCHEMA_OK");
        assertThat(stages(file)).as("the stages were written once, by the winner")
                .containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK");
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("the winner's copy was not deleted by the loser")
                .isEqualTo(1);
        var download = draft.smo().download(fileAt(draft.submissionId(), id) + "/content");
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(Samples.dependencyCheckXml());
        pass();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
    }

    @Test
    void aPassThatLosesTheRaceToAPassThatRejectedTheFileLeavesNoCleanCopyBehind() {
        Draft draft = draft();
        String id = uploadedId(draft, "dc.xml");
        pass();
        scanner.beforeNextFetch(() -> arrange.rejectFile(id));

        pass();

        assertThat(file(draft, id).get("status").asString()).isEqualTo("REJECTED_MALWARE");
        // The winner had already swept (its quarantine key is cleared), so no sweep can find a clean copy the loser
        // wrote afterwards: only the loser's own removal can. The orphaned quarantine blob is the test endpoint's, so
        // only the clean zone, which the loser is responsible for, is asserted.
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("the loser removed the copy it wrote").isZero();
        pass();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
    }

    @Test
    void aScannerThatCannotBeAskedLeavesTheFileWaitingUntilItsAttemptRunsOut() {
        Draft draft = draft();
        String id = uploadedId(draft, "dc.xml");
        pass();

        scanner.unreachable(true);
        pass();
        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        scanner.unreachable(false);
        pass();

        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCHEMA_OK");
    }

    @Test
    void aCleanCopyThatCannotBeFetchedIsFetchedOnTheNextPassAndRetriedWhenTheAttemptIsDue() {
        Draft draft = draft();
        String first = uploadedId(draft, "first.xml");
        pass();
        scanner.failNextFetches(1);
        pass();
        assertThat(file(draft, first).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        pass();
        assertThat(file(draft, first).get("status").asString()).isEqualTo("SCHEMA_OK");

        String second = uploadedId(draft, "second.xml");
        pass();
        scanner.failNextFetches(1);
        clock.advance(PAST_THE_TIMEOUT);
        pass();
        JsonNode retried = file(draft, second);
        assertThat(retried.get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        assertThat(retried.get("scanAttempts").asInt()).as("the attempt was due, so it was submitted again")
                .isEqualTo(2);
    }

    @Test
    void aCleanStoreThatCannotBeWrittenLeavesTheFileForTheNextPass() {
        Draft draft = draft();
        String id = uploadedId(draft, "dc.xml");
        pass();

        fileStore.refuseWrites(true);
        pass();
        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        fileStore.refuseWrites(false);
        pass();

        assertThat(file(draft, id).get("status").asString()).isEqualTo("SCHEMA_OK");
    }

    @Test
    void aVerdictOfUnchangedWithOtherBytesIsRejectedAsAMismatch() {
        Draft draft = draft();

        JsonNode file = settled(draft, DEPENDENCY_CHECK, "swapped_tampered.xml", Samples.dependencyCheckXml());

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_HASH_MISMATCH");
        assertThat(file.get("rejectCode").asString()).isEqualTo("HASH_MISMATCH");
        assertThat(file.get("message").asString()).contains("rejected").contains("Upload the file again");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).isZero();
    }

    @Test
    void atMostTenFilesAreWithTheScannerAtOnce() {
        Draft draft = draft();
        for (int n = 0; n < 12; n++) {
            uploadedId(draft, "file" + n + "_never.xml");
        }

        pass();

        assertThat(scanner.submissions()).isEqualTo(10);
        assertThat(statuses(draft)).containsExactlyElementsOf(
                List.of("SCAN_SUBMITTED", "SCAN_SUBMITTED", "SCAN_SUBMITTED", "SCAN_SUBMITTED", "SCAN_SUBMITTED",
                        "SCAN_SUBMITTED", "SCAN_SUBMITTED", "SCAN_SUBMITTED", "SCAN_SUBMITTED", "SCAN_SUBMITTED",
                        "QUARANTINED", "QUARANTINED"));
        assertThat(ApiClient.json(draft.smo().get(filesOf(draft.submissionId()))).get(11).get("scanAttempts").asInt())
                .as("the newest has not been submitted").isZero();

        scanner.finishStalled();
        pass();

        assertThat(statuses(draft).subList(0, 10)).containsOnly("SCHEMA_OK");
        assertThat(statuses(draft).subList(10, 12)).as("the freed slots go to the waiting files, oldest first")
                .containsOnly("SCAN_SUBMITTED");
        assertThat(scanner.submissions()).isEqualTo(12);
    }

    private List<String> statuses(Draft draft) {
        List<String> statuses = new ArrayList<>();
        ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))
                .forEach(file -> statuses.add(file.get("status").asString()));
        return statuses;
    }
}
