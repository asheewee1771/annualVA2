package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 45 (ticket 40): the VA POC's dashboard lists the Exemption requests waiting on a decision, oldest first, with
 * what the SMO/Tech Lead sent. The list carries what a person wrote, so every read is audited.
 */
class ExemptionQueueTest extends ExemptionSeam1 {

    @Test
    void theQueueListsThePendingRequestsOldestFirstWithTheirEvidence() {
        Draft draft = draft();
        String file = settledEvidence(draft, BURP_SUITE, "va.pdf", Samples.pdf()).get("id").asString();
        JsonNode first = requested(draft.smo(), draft.systemId(),
                exemption(PRIOR_VA, "2027-07-15", "Acme VA, report AC-1.", file));
        clock.advance(Duration.ofMinutes(1));
        ApiClient smoB = signedInAs(SMO_B);
        JsonNode second = requested(smoB, idOf("SYS-F2"), scheduledVa());
        ApiClient poc = vaPoc();
        // The audit log is shared by the suite and lists the newest first by when it happened, so another test's read at
        // a later business date comes first: this read's entry is the one written since.
        Set<String> before = new HashSet<>();
        viewed().forEach(item -> before.add(item.get("id").asString()));

        var response = poc.get(QUEUE);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        JsonNode queue = ApiClient.json(response);
        assertThat(queue.get("cycle").asInt()).isEqualTo(2027);
        JsonNode requests = queue.get("requests");
        assertThat(requests).hasSize(2);
        JsonNode oldest = requests.get(0);
        assertThat(oldest.get("exemptionId").asString()).isEqualTo(first.get("id").asString());
        assertThat(oldest.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(oldest.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(oldest.get("systemCode").asString()).isEqualTo("SYS-F1");
        assertThat(oldest.get("systemName").asString()).isEqualTo("File one");
        assertThat(oldest.get("quarter").asInt()).isEqualTo(3);
        assertThat(oldest.get("basis").asString()).isEqualTo(PRIOR_VA);
        assertThat(oldest.get("vaDate").asString()).isEqualTo("2027-07-15");
        assertThat(oldest.get("evidence").asString()).isEqualTo("Acme VA, report AC-1.");
        assertThat(oldest.get("evidenceFileId").asString()).isEqualTo(file);
        assertThat(oldest.get("previousState").asString()).isEqualTo("DRAFT");
        assertThat(oldest.get("sodConflict").asBoolean()).isFalse();
        assertThat(requests.get(1).get("exemptionId").asString()).isEqualTo(second.get("id").asString());
        assertThat(requests.get(1).get("previousState").asString()).isEqualTo("NOT_STARTED");
        assertThat(poc.get("/api/submissions/" + draft.submissionId() + "/files/" + file + "/content").status())
                .as("the VA POC downloads the evidence file").isEqualTo(200);

        JsonNode audited = entries("EXEMPTIONS_VIEWED", draft.systemId());
        assertThat(audited).as("a list over many Systems names none").isEmpty();
        List<JsonNode> written = viewedSince(before);
        assertThat(written).as("one entry for the read").hasSize(1);
        JsonNode entry = written.get(0);
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(entry.get("detail").get("count").asInt()).isEqualTo(2);
        assertThat(entry.toString()).doesNotContain("Acme");
    }

    private JsonNode viewed() {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=EXEMPTIONS_VIEWED");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    private List<JsonNode> viewedSince(Set<String> before) {
        List<JsonNode> since = new ArrayList<>();
        viewed().forEach(item -> {
            if (!before.contains(item.get("id").asString())) {
                since.add(item);
            }
        });
        return since;
    }

    @Test
    void onlyAVaPocReadsTheQueueAndADecidedRequestLeavesIt() {
        Draft draft = draft();
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();

        assertThat(draft.smo().get(QUEUE).status()).isEqualTo(403);
        assertThat(signedInAs(CM).get(QUEUE).status()).isEqualTo(403);
        assertThat(queue(vaPoc())).hasSize(1);
        rejected(vaPoc(), id);

        assertThat(queue(vaPoc())).isEmpty();
        assertThat(vaPoc().get(QUEUE + "?cycle=1999").status()).as("a Cycle the app does not run").isEqualTo(404);
    }
}
