package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Metrics;
import tools.jackson.databind.JsonNode;

/**
 * The spec's "superseded uploads are purged once the submission is Endorsed", as the reviewers decided (2026-10-18): the
 * approved disposal deletes only the superseded file's content, its record stays until the Cycle is disposed of, and a
 * download of it answers a plain "disposed" problem.
 */
class DisposalSupersededTest extends DisposalSeam1 {

    @AfterEach
    void auditFaultOff() {
        arrange.auditFault("off");
    }

    /** SYS-F1's VA Submission with its first scan replaced by a rescan, taken through review to Endorsed. */
    private record Endorsed(Draft draft, String first, String rescan) {}

    private Endorsed endorsedWithARescan() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = parsedDc(draft, "first.json", dcReportAt(SCANNED));
        String rescan = replacedAndSettled(draft, first, "rescan.json",
                jsonReportOf("{\"name\":\"throwaway-project\",\"reportDate\":\"" + SCANNED + "\"}",
                        dependency(PKG_A, "/srv/alpha.js", "CVE-2027-0001", "9.8"))).get("id").asString();
        enteredByHandAndNotApplicable(draft);
        proposeEvery(draft);
        submitted(draft.smo(), sub);
        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);
        passed(cm, sub);
        return new Endorsed(draft, first, rescan);
    }

    private static String contentOf(String submissionId, String fileId) {
        return fileAt(submissionId, fileId) + "/content";
    }

    @Test
    void aSupersededFileIsListedOnlyOnceItsSubmissionIsEndorsed() {
        Endorsed endorsed = endorsedWithARescan();
        retention();
        assertThat(disposalLists(vaPoc())).as("Awaiting Endorsement: nothing is due yet").isEmpty();

        endorsed(vaPoc(), endorsed.draft().submissionId());
        retention();

        JsonNode list = listIn(vaPoc(), "DRAFT");
        assertThat(items(list)).containsExactly("SUPERSEDED_FILE " + endorsed.first() + " SUPERSEDED_AFTER_ENDORSEMENT");
        assertThat(list.get("cutoff").isNull()).as("it disposes of no Cycle").isTrue();
        assertThat(list.get("items").get(0).get("systemCode").asString()).isEqualTo("SYS-F1");
    }

    @Test
    void anApprovedEarlyDisposalDeletesOnlyTheContentAndADownloadAnswersDisposed() {
        Endorsed endorsed = endorsedWithARescan();
        String sub = endorsed.draft().submissionId();
        endorsed(vaPoc(), sub);
        retention();
        String listId = listIn(vaPoc(), "DRAFT").get("id").asString();
        approved(vaPoc(), listId);
        Set<String> tombstonesBefore = auditIds("DISPOSAL_EXECUTED");

        retention();

        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).doesNotContain(endorsed.first()).contains(endorsed.rescan());
        ApiClient smo = signedInAs(SMO_A);
        JsonNode kept = status(smo, sub, endorsed.first());
        assertThat(kept.get("supersededBy").asString()).as("the record stays, with its links").isEqualTo(endorsed.rescan());
        assertThat(kept.get("contentDisposedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(status(smo, sub, endorsed.rescan()).get("contentDisposedAt").isNull()).isTrue();

        ApiClient.Download disposed = smo.download(contentOf(sub, endorsed.first()));
        assertThat(disposed.status()).isEqualTo(410);
        JsonNode problem = ApiClient.json(disposed);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/disposed");
        assertThat(problem.get("detail").asString()).contains("disposed of");
        assertThat(smo.download(contentOf(sub, endorsed.rescan())).status()).isEqualTo(200);
        JsonNode refusal = entries("FILE_DOWNLOADED", endorsed.draft().systemId()).get(1);
        assertThat(refusal.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refusal.get("detail").get("reason").asString()).isEqualTo("disposed");
        assertThat(refusal.get("detail").get("file_id").asString()).isEqualTo(endorsed.first());

        List<JsonNode> tombstones = writtenSince("DISPOSAL_EXECUTED", tombstonesBefore);
        assertThat(tombstones).hasSize(1);
        assertThat(tombstones.getFirst().get("detail").get("superseded_files").asInt()).isEqualTo(1);
        assertThat(tombstones.getFirst().get("detail").get("files").asInt()).as("no row was deleted").isZero();
        retention();
        assertThat(disposalLists(vaPoc())).as("disposed of once: nothing more is due").hasSize(1);
    }

    @Test
    void contentTheStoreCannotRemoveStopsTheDisposalBeforeAnyRecordChangesAndTheNextPassFinishesIt() {
        Endorsed endorsed = endorsedWithARescan();
        String sub = endorsed.draft().submissionId();
        endorsed(vaPoc(), sub);
        retention();
        String listId = listIn(vaPoc(), "DRAFT").get("id").asString();
        approved(vaPoc(), listId);
        fileStore.refuseDeletes(true);

        retention();

        assertThat(listIn(vaPoc(), "APPROVED").get("id").asString()).as("still waiting for the executor").isEqualTo(listId);
        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).contains(endorsed.first());
        assertThat(status(signedInAs(SMO_A), sub, endorsed.first()).get("contentDisposedAt").isNull()).isTrue();

        fileStore.refuseDeletes(false);
        retention();

        assertThat(listIn(vaPoc(), "EXECUTED").get("id").asString()).isEqualTo(listId);
        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).doesNotContain(endorsed.first());
        assertThat(status(signedInAs(SMO_A), sub, endorsed.first()).get("contentDisposedAt").isNull()).isFalse();
    }

    /**
     * Ticket 67: both retention jobs catch their own failure and return, so the scheduler sees nothing wrong; their series
     * show it instead. A listing that could not be written and a disposal the store could not finish are not completed
     * runs; the runs after the cause is gone are.
     */
    @Test
    void aListingOrADisposalThatFailedIsNotACompletedRunOfItsRetentionJob() {
        Endorsed endorsed = endorsedWithARescan();
        endorsed(vaPoc(), endorsed.draft().submissionId());
        Metrics before = metrics();
        clock.advance(Duration.ofMinutes(5));

        arrange.auditFault("DISPOSAL_LISTED");
        var listingFailed = arrange.scheduledJob("RetentionJob.list");
        Metrics afterListingFailed = metrics();
        arrange.auditFault("off");
        assertThat(disposalLists(vaPoc())).as("the listing rolled back").isEmpty();
        clock.advance(Duration.ofMinutes(5));
        arrange.scheduledJob("RetentionJob.list");
        Metrics listed = metrics();
        double listedAt = clock.instant().toEpochMilli() / 1000.0;

        String listId = listIn(vaPoc(), "DRAFT").get("id").asString();
        approved(vaPoc(), listId);
        clock.advance(Duration.ofMinutes(5));
        fileStore.refuseDeletes(true);
        var disposalFailed = arrange.scheduledJob("RetentionJob.execute");
        Metrics afterDisposalFailed = metrics();
        fileStore.refuseDeletes(false);
        clock.advance(Duration.ofMinutes(5));
        arrange.scheduledJob("RetentionJob.execute");

        assertThat(listingFailed.status()).as("the job caught it").isEqualTo(200);
        assertThat(afterListingFailed.value(Metrics.job("retention_list"))).as("a listing that failed")
                .isEqualTo(before.value(Metrics.job("retention_list")));
        assertThat(listed.value(Metrics.job("retention_list"))).isCloseTo(listedAt, within(0.001));
        assertThat(listed.value(Metrics.job("retention_execute")))
                .as("the listing's executor pass, with no list waiting, is a completed run of the executor")
                .isCloseTo(listedAt, within(0.001));
        assertThat(disposalFailed.status()).as("the executor caught it").isEqualTo(200);
        assertThat(listIn(vaPoc(), "EXECUTED").get("id").asString()).isEqualTo(listId);
        assertThat(afterDisposalFailed.value(Metrics.job("retention_execute"))).as("a disposal that failed")
                .isEqualTo(listed.value(Metrics.job("retention_execute")));
        assertThat(metrics().value(Metrics.job("retention_execute")))
                .isCloseTo(clock.instant().toEpochMilli() / 1000.0, within(0.001));
    }
}
