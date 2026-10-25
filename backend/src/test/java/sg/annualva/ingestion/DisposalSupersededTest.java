package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * The spec's "superseded uploads are purged once the submission is Endorsed", as the reviewers decided (2026-10-18): the
 * approved disposal deletes only the superseded file's content, its record stays until the Cycle is disposed of, and a
 * download of it answers a plain "disposed" problem.
 */
class DisposalSupersededTest extends DisposalSeam1 {

    /** SYS-F1's VA Submission with its first scan replaced by a rescan, taken through review to Endorsed. */
    private record Endorsed(Draft draft, String first, String rescan) {}

    private Endorsed endorsedWithARescan() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = parsedDc(draft, "first.json", dcReportAt(SCANNED));
        String rescan = replacedAndSettled(draft, first, "rescan.json",
                jsonReportOf("{\"name\":\"throwaway-project\",\"reportDate\":\"" + SCANNED + "\"}",
                        dependency(PKG_A, "/srv/alpha.js", "CVE-2027-0001", "9.8"))).get("id").asString();
        modeSet(draft.smo(), sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "The System has no web interface."));
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
}
