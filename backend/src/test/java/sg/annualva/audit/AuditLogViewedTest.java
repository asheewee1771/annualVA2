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
 * Manager makes writes one {@code AUDIT_LOG_VIEWED} entry, by the reader in the role they hold, with what they asked for
 * (the System and VA Submission as the entry's ids, the other filters and the page as fixed detail) and how many entries
 * came back; never anything the entries say. A refused read is the {@code REQUEST_REFUSED} of every refusal, and a VA
 * Submission outside the reader's scope is a 404, unaudited as every 404 is (ADR 0014).
 */
class AuditLogViewedTest extends AuditSeam1 {

    private static final Set<String> FILTER_KEYS = Set.of("filter_actor", "filter_action", "filter_from", "filter_to",
            "page", "count");

    @Test
    void aVaPocsReadIsOneEntryWithTheFiltersAskedForAndNothingTheEntriesSay() {
        ApiClient poc = reader();
        String reader = userId(poc);
        String filtered = "/api/audit-entries?actor=" + reader + "&action=SIGN_IN&from=" + today() + "&to=" + today()
                + "&page=0&size=5";

        var read = poc.get(filtered);

        assertThat(read.status()).isEqualTo(200);
        List<JsonNode> viewed = entries(poc, "action=AUDIT_LOG_VIEWED&actor=" + reader);
        assertThat(viewed).as("the read just made, and not the one that lists it").hasSize(1);
        JsonNode entry = viewed.getFirst();
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(entry.get("systemId").isNull()).isTrue();
        assertThat(entry.get("submissionId").isNull()).isTrue();
        JsonNode detail = entry.get("detail");
        assertThat(detail.get("filter_actor").asString()).isEqualTo(reader);
        assertThat(detail.get("filter_action").asString()).isEqualTo("SIGN_IN");
        assertThat(detail.get("filter_from").asString()).isEqualTo(today());
        assertThat(detail.get("filter_to").asString()).isEqualTo(today());
        assertThat(detail.get("page").asInt()).isZero();
        assertThat(detail.get("count").asInt()).isEqualTo(ApiClient.json(read).get("items").size());
        assertThat(detail.propertyNames()).isSubsetOf(FILTER_KEYS);
    }

    @Test
    void aReadAboutOneSystemAndVaSubmissionNamesThemOnTheEntry() {
        ApiClient poc = reader();
        UUID system = UUID.fromString(arrange.system());
        UUID submission = UUID.randomUUID();

        assertThat(poc.get("/api/audit-entries?system=" + system + "&submission=" + submission).status()).isEqualTo(200);

        JsonNode entry = entries(poc, "action=AUDIT_LOG_VIEWED&system=" + system).getFirst();
        assertThat(entry.get("systemId").asString()).isEqualTo(system.toString());
        assertThat(entry.get("submissionId").asString()).isEqualTo(submission.toString());
        assertThat(entry.get("detail").get("count").asInt()).isZero();
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
