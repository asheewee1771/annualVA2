package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Story 87, IM8 ac-1 and ac-2: a disposal list is read and approved by a VA POC alone, approving needs a fresh sign-in,
 * only the list waiting for approval can be approved, and the executor then disposes of exactly what it names and writes
 * one tombstone audit entry.
 */
class DisposalApprovalTest extends DisposalSeam1 {

    private static final String APPROVED_ACTION = "DISPOSAL_APPROVED";
    private static final String EXECUTED_ACTION = "DISPOSAL_EXECUTED";

    /** An export made and its 24 hours passed, so the next retention pass lists it; its id. */
    private String expiredExport() {
        String export = exportMade(vaPoc());
        clock.advance(Duration.ofHours(25));
        arrange.exports();
        return export;
    }

    @Test
    void onlyAVaPocReadsOrApprovesADisposalList() {
        expiredExport();
        retention();
        Draft draft = draft();
        String listId = listIn(vaPoc(), "DRAFT").get("id").asString();

        for (ApiClient other : new ApiClient[] {draft.smo(), signedInAs(CM), signedInAs("disposal.nobody@ci.annualva.test")}) {
            assertThat(other.get(DISPOSAL_LISTS).status()).isEqualTo(403);
            assertThat(approve(other, listId).status()).isEqualTo(403);
        }
        assertThat(anotherBrowser().get(DISPOSAL_LISTS).status()).isEqualTo(401);
        assertThat(listIn(vaPoc(), "DRAFT").get("id").asString()).as("still waiting").isEqualTo(listId);
    }

    @Test
    void approvingNeedsAFreshSignInAndIsAudited() {
        expiredExport();
        retention();
        ApiClient poc = vaPoc();
        String listId = listIn(poc, "DRAFT").get("id").asString();
        clock.advance(Duration.ofMinutes(6));

        var refused = approve(poc, listId);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("stepUpPath").asString())
                .isEqualTo("/auth/step-up?intent=disposal-approve");
        assertThat(listIn(poc, "DRAFT").get("id").asString()).as("nothing was approved").isEqualTo(listId);

        SignIn.stepUp(poc, issuer, "/auth/step-up?intent=disposal-approve&returnTo=/va-poc/disposal",
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        poc.withCsrf();
        Set<String> before = auditIds(APPROVED_ACTION);
        JsonNode answered = approved(poc, listId);

        assertThat(answered.get("status").asString()).isEqualTo("APPROVED");
        assertThat(answered.get("approvedBy").asString()).isEqualTo(userId(poc));
        assertThat(answered.get("approvedAt").asString()).isEqualTo(clock.instant().toString());
        List<JsonNode> entries = writtenSince(APPROVED_ACTION, before);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entries.getFirst().get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(entries.getFirst().get("detail").get("disposal_list_id").asString()).isEqualTo(listId);
    }

    @Test
    void theExecutorDisposesOfWhatTheApprovedListNamesAndWritesOneTombstone() {
        ApiClient requester = vaPoc();
        String export = exportMade(requester);
        clock.advance(Duration.ofHours(25));
        arrange.exports();
        retention();
        String listId = listIn(vaPoc(), "DRAFT").get("id").asString();
        approved(vaPoc(), listId);
        Set<String> before = auditIds(EXECUTED_ACTION);

        retention();

        JsonNode executed = listIn(vaPoc(), "EXECUTED");
        assertThat(executed.get("id").asString()).isEqualTo(listId);
        assertThat(executed.get("executedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(items(executed)).as("the list stays as the record of what was disposed of")
                .containsExactly("EXPORT " + export + " EXPORT_EXPIRED");
        assertThat(vaPoc().get("/api/exports/" + export).status()).as("the export's row is gone").isEqualTo(404);
        List<JsonNode> tombstones = writtenSince(EXECUTED_ACTION, before);
        assertThat(tombstones).hasSize(1);
        JsonNode detail = tombstones.getFirst().get("detail");
        assertThat(detail.get("disposal_list_id").asString()).isEqualTo(listId);
        assertThat(detail.get("exports").asInt()).isEqualTo(1);
        assertThat(detail.get("submissions").asInt()).isZero();
        assertThat(detail.get("files").asInt()).isZero();
        assertThat(detail.get("superseded_files").asInt()).isZero();
        assertThat(detail.get("systems").asInt()).isZero();
        assertThat(detail.get("audit_entries").asInt()).isZero();
        assertThat(tombstones.getFirst().get("actorId").isNull()).as("the job acts for nobody").isTrue();

        retention();
        assertThat(disposalLists(vaPoc())).as("nothing is due any more, so no new list").hasSize(1);
        assertThat(writtenSince(EXECUTED_ACTION, before)).as("executed once").hasSize(1);
    }

    @Test
    void onlyTheListWaitingForApprovalCanBeApproved() {
        String first = expiredExport();
        retention();
        String replaced = listIn(vaPoc(), "DRAFT").get("id").asString();
        String second = expiredExport();
        retention();
        JsonNode waiting = listIn(vaPoc(), "DRAFT");
        String waitingId = waiting.get("id").asString();
        assertThat(items(waiting)).containsExactly("EXPORT " + first + " EXPORT_EXPIRED",
                "EXPORT " + second + " EXPORT_EXPIRED");
        assertThat(listIn(vaPoc(), "SUPERSEDED").get("id").asString()).isEqualTo(replaced);
        retention();
        assertThat(listIn(vaPoc(), "DRAFT").get("id").asString()).as("the same records keep the same list")
                .isEqualTo(waitingId);
        ApiClient poc = vaPoc();
        Set<String> before = auditIds(APPROVED_ACTION);

        assertConflict(approve(poc, replaced), "list-not-draft");
        approved(poc, waitingId);
        assertConflict(approve(poc, waitingId), "list-not-draft");
        assertNotFound(approve(poc, "00000000-0000-0000-0000-000000000000"));

        List<JsonNode> entries = writtenSince(APPROVED_ACTION, before);
        assertThat(entries).extracting(entry -> entry.get("outcome").asString())
                .containsExactlyInAnyOrder("REFUSED", "SUCCESS", "REFUSED");
        assertThat(entries).filteredOn(entry -> entry.get("outcome").asString().equals("REFUSED"))
                .allSatisfy(entry -> assertThat(entry.get("detail").get("reason").asString()).isEqualTo("list-not-draft"));
        retention();
        assertThat(listIn(vaPoc(), "EXECUTED").get("id").asString()).isEqualTo(waitingId);
    }
}
