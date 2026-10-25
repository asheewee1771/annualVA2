package sg.annualva.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Story 84: the VA POCs read the whole audit log, paged and filtered by actor, action, System, VA Submission and date. A
 * Cyber Manager reads only the history of what they reviewed (story 85, {@code CmAuditHistoryTest}); nobody else reads it.
 */
class AuditReadTest extends AuditSeam1 {

    @Test
    void onlyAVaPocOrACyberManagerCanReadTheLogAndEveryoneElseIsRefused() {
        assertThat(api.get("/api/audit-entries").status()).as("no session").isEqualTo(401);

        String system = arrange.system();
        arrange.grant("SMO", "reads.smo@ci.annualva.test", system);
        arrange.grant("CYBER_MANAGER", "reads.manager@ci.annualva.test", system);
        var smo = anotherBrowser();
        SignIn.signedIn(smo, issuer, TestIdentity.of("af-a601", "reads.smo@ci.annualva.test", "Reads SMO"));
        var manager = anotherBrowser();
        SignIn.signedIn(manager, issuer, TestIdentity.of("af-a602", "reads.manager@ci.annualva.test", "Reads Manager"));
        var nobody = anotherBrowser();
        SignIn.signedIn(nobody, issuer, TestIdentity.of("af-a603", "reads.nobody@ci.annualva.test", "Reads Nobody"));

        for (ApiClient other : List.of(smo, nobody)) {
            var response = other.get("/api/audit-entries");
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("SIGN_IN");
        }
        var unreviewed = manager.get("/api/audit-entries");
        assertThat(unreviewed.status()).as("a Cyber Manager who reviewed nothing reads nothing").isEqualTo(200);
        assertThat(ApiClient.json(unreviewed).get("total").asLong()).isZero();
        assertThat(reader().get("/api/audit-entries").status()).isEqualTo(200);
    }

    @Test
    void theLogIsNarrowedByActorActionSystemSubmissionAndDate() {
        ApiClient poc = reader();
        UUID systemOne = UUID.randomUUID();
        UUID systemTwo = UUID.randomUUID();
        UUID submissionOne = UUID.randomUUID();
        UUID submissionTwo = UUID.randomUUID();
        writeAbout(poc, systemOne, submissionOne);
        clock.advance(Duration.ofMinutes(1));
        writeAbout(poc, systemTwo, submissionTwo);
        clock.advance(Duration.ofMinutes(1));
        writeAbout(poc, systemTwo, submissionOne);
        String pocId = userId(poc);

        assertThat(entries(poc, "system=" + systemOne)).hasSize(1);
        assertThat(entries(poc, "system=" + systemTwo)).hasSize(2);
        assertThat(entries(poc, "submission=" + submissionOne)).hasSize(2);
        assertThat(entries(poc, "submission=" + submissionTwo)).hasSize(1);
        assertThat(entries(poc, "system=" + systemTwo + "&submission=" + submissionOne)).hasSize(1);
        assertThat(entries(poc, "system=" + UUID.randomUUID())).isEmpty();
        assertThat(entries(poc, "actor=" + pocId + "&action=ROLE_GRANTED")).hasSize(3);
        assertThat(entries(poc, "actor=" + pocId + "&action=SIGN_IN")).hasSize(1);
        assertThat(entries(poc, "actor=" + UUID.randomUUID())).isEmpty();
        assertThat(entries(poc, "action=SIGN_OUT")).isEmpty();
    }

    @Test
    void theDateFilterFollowsTheBusinessDayInSingaporeNotUtc() {
        // 17:30 UTC on the 10th is 01:30 on the 11th in Singapore.
        clock.set(LocalDate.of(2033, 3, 10).atTime(17, 30).toInstant(ZoneOffset.UTC));
        ApiClient poc = reader();
        UUID system = UUID.randomUUID();
        writeAbout(poc, system, UUID.randomUUID());

        // The reads themselves are entries about the System too (AUDIT_LOG_VIEWED); only what was written counts.
        String query = "system=" + system + "&action=ROLE_GRANTED";
        assertThat(allPages(poc, query + "&from=2033-03-11&to=2033-03-11")).as("its Singapore day").hasSize(1);
        assertThat(allPages(poc, query + "&from=2033-03-10&to=2033-03-10")).as("its UTC day").isEmpty();
        assertThat(allPages(poc, query + "&from=2033-03-10&to=2033-03-11")).hasSize(1);
        assertThat(allPages(poc, query + "&from=2033-03-12")).isEmpty();
        assertThat(allPages(poc, query + "&to=2033-03-10")).isEmpty();
        assertThat(allPages(poc, query + "&to=2033-03-11")).hasSize(1);
    }

    @Test
    void thePagesTileTheAnswerNewestFirst() {
        ApiClient poc = reader();
        UUID system = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            writeAbout(poc, system, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));
        }

        // The reads are entries about the System too (AUDIT_LOG_VIEWED), so only the action written is paged.
        String written = "/api/audit-entries?action=ROLE_GRANTED&system=" + system + "&from=" + today() + "&to="
                + today() + "&size=2&page=";
        Set<String> seen = new HashSet<>();
        Instant previous = Instant.MAX;
        int[] expectedSizes = {2, 2, 1};
        for (int page = 0; page < 3; page++) {
            JsonNode answer = ApiClient.json(poc.get(written + page));
            assertThat(answer.get("total").asLong()).isEqualTo(5);
            assertThat(answer.get("page").asInt()).isEqualTo(page);
            assertThat(answer.get("size").asInt()).isEqualTo(2);
            assertThat(answer.get("items")).hasSize(expectedSizes[page]);
            for (JsonNode entry : answer.get("items")) {
                Instant at = Instant.parse(entry.get("occurredAt").asString());
                assertThat(at).isBefore(previous);
                previous = at;
                assertThat(seen.add(entry.get("id").asString())).as("no entry on two pages").isTrue();
            }
        }
        JsonNode beyond = ApiClient.json(poc.get(written + 3));
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(5);
    }

    @Test
    void entriesWithTheSameInstantComeBackNewestWrittenFirst() {
        ApiClient poc = reader();
        UUID system = UUID.randomUUID();
        List<String> written = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID submission = UUID.randomUUID();
            writeAbout(poc, system, submission);
            written.add(0, submission.toString());
        }

        List<String> listed = entries(poc, "system=" + system).stream()
                .map(entry -> entry.get("submissionId").asString()).toList();

        assertThat(listed).as("the clock never moved, so only the write order can say which is newest")
                .containsExactlyElementsOf(written);
    }

    @Test
    void aBadQueryIsABadRequestThatDoesNotEchoWhatWasSent() {
        ApiClient poc = reader();
        for (String query : List.of("size=0", "size=201", "page=-1", "page=x", "actor=CVE-9999-SENTINEL",
                "system=CVE-9999-SENTINEL", "submission=CVE-9999-SENTINEL", "action=CVE-9999-SENTINEL",
                "action=sign_in", "from=CVE-9999-SENTINEL", "to=2033-13-45", "from=2033-03-11&to=2033-03-10")) {
            var response = poc.get("/api/audit-entries?" + query);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.header("content-type")).startsWith("application/problem+json");
            assertThat(response.body()).as(query).doesNotContain("SENTINEL").doesNotContain("sign_in");
            assertThat(ApiClient.json(response).get("traceId").asString()).isNotBlank();
        }
    }

    @Test
    void aPageHoldsAtMostTwoHundredEntries() {
        ApiClient poc = reader();

        JsonNode answer = ApiClient.json(poc.get("/api/audit-entries?size=200&from=" + today() + "&to=" + today()));

        assertThat(answer.get("size").asInt()).isEqualTo(200);
        assertThat(answer.get("items").size()).isLessThanOrEqualTo(200);
    }

    @Test
    void thereIsNoWayToChangeOrRemoveAnEntryThroughTheApi() {
        ApiClient poc = reader().withCsrf();
        String url = "/api/audit-entries/" + UUID.randomUUID();

        assertThat(poc.postJson("/api/audit-entries", "{}").status()).isIn(404, 405);
        assertThat(poc.get(url).status()).isEqualTo(404);
    }

    /** Writes one entry about a System and a VA Submission, as the poc, through a command that audits. */
    private void writeAbout(ApiClient poc, UUID system, UUID submission) {
        var response = poc.withCsrf().postJson("/api/probe/audited?system=" + system + "&submission=" + submission
                + "&finding=" + UUID.randomUUID() + "&ending=commit", "");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }
}
