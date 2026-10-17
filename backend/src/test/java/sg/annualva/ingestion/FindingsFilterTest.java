package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 28 (ticket 19): {@code GET /api/submissions/{id}/findings} narrows by report type, severity, review state and
 * "needs attention" (no proposal yet, or a Disputed one), alone or together, and the total is the number that match. A value
 * that is not one of the choices is a 400. The VA Submission here holds seven Dependency-Check Findings (three Critical, one
 * High, two Medium, one Low) and the four of the Burp sample.
 */
class FindingsFilterTest extends ProposalSeam1 {

    private Draft draftWithTwoReports() {
        Draft draft = draftWithFindings();
        settled(draft, BURP_SUITE, "issues.xml", Samples.burpSample());
        return draft;
    }

    private static String query(String submissionId, String filter) {
        return findingsOf(submissionId) + "?size=500&" + filter;
    }

    private static JsonNode read(ApiClient client, String submissionId, String filter) {
        var response = client.get(query(submissionId, filter));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    private static List<String> identifiers(JsonNode page) {
        List<String> identifiers = new ArrayList<>();
        page.get("items").forEach(item -> identifiers.add(item.get("identifier").asString()));
        return identifiers;
    }

    @Test
    void theReportTypeNarrowsTheListAndTheTotalIsTheNumberThatMatch() {
        Draft draft = draftWithTwoReports();

        assertThat(read(draft.smo(), draft.submissionId(), "").get("total").asInt()).isEqualTo(11);
        JsonNode dependencyCheck = read(draft.smo(), draft.submissionId(), "reportType=DEPENDENCY_CHECK");
        assertThat(dependencyCheck.get("total").asInt()).isEqualTo(7);
        dependencyCheck.get("items").forEach(item -> assertThat(item.get("reportType").asString()).isEqualTo(DEPENDENCY_CHECK));
        JsonNode burp = read(draft.smo(), draft.submissionId(), "reportType=BURP_SUITE");
        assertThat(burp.get("total").asInt()).isEqualTo(4);
        burp.get("items").forEach(item -> assertThat(item.get("reportType").asString()).isEqualTo(BURP_SUITE));
        assertThat(read(draft.smo(), draft.submissionId(), "reportType=SECURE_CONFIGURATION").get("items")).isEmpty();
        assertThat(draft.smo().get(query(draft.submissionId(), "reportType=NESSUS")).status()).isEqualTo(400);
    }

    @Test
    void theSeverityNarrowsTheListToOneOrSeveralSeveritiesWrittenAsRepeatedOrCommaSeparatedValues() {
        Draft draft = draftWithFindings();

        JsonNode critical = read(draft.smo(), draft.submissionId(), "severity=CRITICAL");
        assertThat(critical.get("total").asInt()).isEqualTo(3);
        critical.get("items").forEach(item -> assertThat(item.get("severity").asString()).isEqualTo("CRITICAL"));
        List<String> criticalOrHigh = identifiers(read(draft.smo(), draft.submissionId(), "severity=CRITICAL&severity=HIGH"));
        assertThat(criticalOrHigh).hasSize(4).contains("GHSA-aaaa-bbbb-cccc");
        assertThat(identifiers(read(draft.smo(), draft.submissionId(), "severity=HIGH,CRITICAL"))).isEqualTo(criticalOrHigh);
        assertThat(read(draft.smo(), draft.submissionId(), "severity=INFORMATIONAL").get("total").asInt()).isZero();
        assertThat(draft.smo().get(query(draft.submissionId(), "severity=SEVERE")).status()).isEqualTo(400);
    }

    @Test
    void theReviewStateNarrowsTheListToTheFindingsWhoseCurrentProposalIsInThatState() {
        Draft draft = draftWithFindings();
        String pending = findingId(draft, "CVE-2021-44228");
        String confirmed = findingId(draft, "CVE-2021-45046");
        String disputed = findingId(draft, "CVE-2022-42889");
        for (String id : List.of(pending, confirmed, disputed)) {
            proposed(draft.smo(), id, remediated("Patched."));
        }
        arrange.findingReview(confirmed, "CONFIRMED");
        arrange.findingReview(disputed, "DISPUTED");

        assertThat(identifiers(read(draft.smo(), draft.submissionId(), "reviewState=PENDING"))).containsExactly("CVE-2021-44228");
        assertThat(identifiers(read(draft.smo(), draft.submissionId(), "reviewState=CONFIRMED"))).containsExactly("CVE-2021-45046");
        assertThat(identifiers(read(draft.smo(), draft.submissionId(), "reviewState=DISPUTED"))).containsExactly("CVE-2022-42889");
        assertThat(read(draft.smo(), draft.submissionId(), "reviewState=PENDING").get("total").asInt())
                .as("a Finding with no proposal has no review state, so is in none of them").isEqualTo(1);
        assertThat(draft.smo().get(query(draft.submissionId(), "reviewState=MAYBE")).status()).isEqualTo(400);
    }

    @Test
    void needsAttentionIsEveryFindingWithNoProposalYetOrADisputedOne() {
        Draft draft = draftWithFindings();
        assertThat(read(draft.smo(), draft.submissionId(), "needsAttention=true").get("total").asInt())
                .as("nothing is proposed yet").isEqualTo(7);

        String proposedOnly = findingId(draft, "CVE-2021-44228");
        String confirmed = findingId(draft, "CVE-2021-45046");
        String disputed = findingId(draft, "CVE-2022-42889");
        for (String id : List.of(proposedOnly, confirmed, disputed)) {
            proposed(draft.smo(), id, remediated("Patched."));
        }
        arrange.findingReview(confirmed, "CONFIRMED");
        arrange.findingReview(disputed, "DISPUTED");

        List<String> needing = identifiers(read(draft.smo(), draft.submissionId(), "needsAttention=true"));
        assertThat(needing).as("the Disputed one and the four with no proposal").hasSize(5)
                .contains("CVE-2022-42889").doesNotContain("CVE-2021-44228", "CVE-2021-45046");
        assertThat(read(draft.smo(), draft.submissionId(), "needsAttention=false").get("total").asInt())
                .as("false is no filter").isEqualTo(7);

        proposed(draft.smo(), disputed, remediated("Patched again."));
        assertThat(identifiers(read(draft.smo(), draft.submissionId(), "needsAttention=true")))
                .as("a new proposal answers the dispute").hasSize(4).doesNotContain("CVE-2022-42889");
    }

    @Test
    void theFiltersApplyTogetherAndPagingFollowsTheFilteredList() {
        Draft draft = draftWithTwoReports();
        proposed(draft.smo(), findingId(draft, "CVE-2021-44228"), remediated("Patched."));

        JsonNode both = read(draft.smo(), draft.submissionId(),
                "reportType=DEPENDENCY_CHECK&severity=CRITICAL&needsAttention=true");
        assertThat(identifiers(both)).containsExactly("CVE-2021-45046", "CVE-2022-42889");
        assertThat(both.get("total").asInt()).isEqualTo(2);

        JsonNode firstPage = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId())
                + "?reportType=DEPENDENCY_CHECK&severity=CRITICAL&page=0&size=2"));
        JsonNode secondPage = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId())
                + "?reportType=DEPENDENCY_CHECK&severity=CRITICAL&page=1&size=2"));
        assertThat(firstPage.get("total").asInt()).as("three match in all").isEqualTo(3);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(read(draft.smo(), draft.submissionId(), "reportType=BURP_SUITE&reviewState=PENDING").get("items")).isEmpty();
    }

    @Test
    void theCyberManagerAndTheVaPocFilterToo() {
        Draft draft = draftWithTwoReports();
        proposed(draft.smo(), findingId(draft, "CVE-2021-44228"), remediated("Patched."));

        for (ApiClient reader : List.of(signedInAs(CM), vaPoc())) {
            assertThat(read(reader, draft.submissionId(), "reviewState=PENDING").get("total").asInt()).isEqualTo(1);
            assertThat(read(reader, draft.submissionId(), "reportType=BURP_SUITE").get("total").asInt()).isEqualTo(4);
        }
        assertNotFound(signedInAs(SMO_B).get(query(draft.submissionId(), "reviewState=PENDING")));
    }

    @Test
    void aFilteredReadIsOneAuditEntryWithTheNumberOfFindingsReturned() {
        Draft draft = draftWithTwoReports();

        read(draft.smo(), draft.submissionId(), "reportType=BURP_SUITE");
        read(draft.smo(), draft.submissionId(), "severity=CRITICAL&needsAttention=true");

        JsonNode entries = entries("FINDINGS_VIEWED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("detail").get("count").asInt()).isEqualTo(3);
        assertThat(entries.get(1).get("detail").get("count").asInt()).isEqualTo(4);
        for (JsonNode entry : entries) {
            assertThat(entry.get("detail").propertyNames()).containsExactly("count");
        }
    }
}
