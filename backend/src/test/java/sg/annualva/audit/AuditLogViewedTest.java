package sg.annualva.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Q11 (decided 2026-10-25), IM8 lm-4, ticket 50: reading the audit log is itself audited. Every read a VA POC or a Cyber
 * Manager makes writes one {@code AUDIT_LOG_VIEWED} entry, by the reader in the role they hold, with the filter ids asked
 * for and the page; never anything the entries say, and about no one System, so a read never shows in a System's history.
 * A refused read is the {@code REQUEST_REFUSED} of every refusal, and a VA Submission outside the reader's scope is a 404,
 * unaudited as every 404 is (ADR 0014).
 */
class AuditLogViewedTest extends AuditSeam1 {

    private static final Set<String> FILTER_KEYS = Set.of("filter_actor", "filter_action", "filter_system",
            "filter_submission", "filter_from", "filter_to", "page");

    @Test
    void aVaPocsReadIsOneEntryWithTheFiltersAskedForAndNothingTheEntriesSay() {
        ApiClient poc = reader();
        String reader = userId(poc);
        UUID system = UUID.randomUUID();
        UUID submission = UUID.randomUUID();

        var read = poc.get("/api/audit-entries?actor=" + reader + "&action=SIGN_IN&system=" + system + "&submission="
                + submission + "&from=" + today() + "&to=" + today() + "&page=1&size=5");

        assertThat(read.status()).isEqualTo(200);
        List<JsonNode> viewed = entries(poc, "action=AUDIT_LOG_VIEWED&actor=" + reader);
        assertThat(viewed).as("the read just made, and not the one that lists it").hasSize(1);
        JsonNode entry = viewed.getFirst();
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(entry.get("systemId").isNull()).as("a read is in no System's history").isTrue();
        assertThat(entry.get("submissionId").isNull()).isTrue();
        assertThat(entry.get("findingId").isNull()).isTrue();
        JsonNode detail = entry.get("detail");
        assertThat(detail.get("filter_actor").asString()).isEqualTo(reader);
        assertThat(detail.get("filter_action").asString()).isEqualTo("SIGN_IN");
        assertThat(detail.get("filter_system").asString()).isEqualTo(system.toString());
        assertThat(detail.get("filter_submission").asString()).isEqualTo(submission.toString());
        assertThat(detail.get("filter_from").asString()).isEqualTo(today());
        assertThat(detail.get("filter_to").asString()).isEqualTo(today());
        assertThat(detail.get("page").asInt()).isEqualTo(1);
        assertThat(detail.propertyNames()).isSubsetOf(FILTER_KEYS);
    }

    @Test
    void aReadWithNoFiltersRecordsThePageAloneAndNeverShowsInASystemsHistory() {
        ApiClient poc = reader();
        String system = arrange.system();

        assertThat(poc.get("/api/audit-entries").status()).isEqualTo(200);
        assertThat(poc.get("/api/audit-entries?system=" + system).status()).isEqualTo(200);

        List<JsonNode> viewed = entries(poc, "action=AUDIT_LOG_VIEWED&actor=" + userId(poc));
        assertThat(viewed).hasSize(2);
        assertThat(viewed.getLast().get("detail").propertyNames()).containsExactly("page");
        assertThat(ApiClient.json(poc.get("/api/audit-entries?system=" + system)).get("total").asLong())
                .as("reading a System's history adds nothing to it").isZero();
    }

    @Test
    void aCyberManagersReadIsAuditedInTheirRoleAndARefusedOrOutOfScopeReadIsNot() {
        String system = arrange.system();
        arrange.grant("CYBER_MANAGER", "viewed.manager@ci.annualva.test", system);
        arrange.grant("SMO", "viewed.smo@ci.annualva.test", system);
        ApiClient manager = anotherBrowser();
        SignIn.signedIn(manager, issuer, TestIdentity.of("af-a611", "viewed.manager@ci.annualva.test", "Viewed Manager"));
        ApiClient smo = anotherBrowser();
        SignIn.signedIn(smo, issuer, TestIdentity.of("af-a612", "viewed.smo@ci.annualva.test", "Viewed SMO"));

        assertThat(manager.get("/api/audit-entries").status()).isEqualTo(200);
        assertThat(manager.get("/api/audit-entries?submission=" + UUID.randomUUID()).status())
                .as("a VA Submission they did not review").isEqualTo(404);
        assertThat(smo.get("/api/audit-entries").status()).isEqualTo(403);

        ApiClient poc = reader();
        List<JsonNode> managers = entries(poc, "action=AUDIT_LOG_VIEWED&actor=" + userId(manager));
        assertThat(managers).as("one entry for the read that was answered").hasSize(1);
        assertThat(managers.getFirst().get("actorRole").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(entries(poc, "action=AUDIT_LOG_VIEWED&actor=" + userId(smo))).isEmpty();
        assertThat(actions(entries(poc, "actor=" + userId(smo)))).contains("REQUEST_REFUSED");
    }
}
