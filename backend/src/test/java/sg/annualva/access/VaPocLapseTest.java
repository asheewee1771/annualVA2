package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.audit.AuditLog;
import sg.annualva.harness.ApiClient;
import sg.annualva.register.RegisterSeam1;
import tools.jackson.databind.JsonNode;

/**
 * Story 80a and IM8 ac-3 (ticket 46): a VA POC's role lapses after 90 days unused like any other, and comes back only
 * through configuration: a deployment whose {@code annualva.va-poc-returns} names them. A Register apply, which returns
 * the SMO/Tech Leads and Cyber Managers it names, does not return a VA POC, and neither does a restart that names nobody.
 */
class VaPocLapseTest extends RegisterSeam1 {

    /** When the suite's application started and configuration granted the VA POC roles: the clock's first instant. */
    private static final Instant STARTED = Instant.parse("2027-08-02T01:00:00Z");

    @Autowired
    private VaPocSync startupSync;

    @Autowired
    private RoleGrantStore grants;

    @Autowired
    private AuditLog audit;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private RoleLapse lapse;

    /** A restart of the application with this deployment configuration. */
    private void restartWith(List<String> returns) {
        new VaPocSync(new VaPocProperties(List.of(POC_ONE, POC_TWO), returns), grants, audit, clock, transaction, lapse)
                .sync();
    }

    private static List<String> roles(ApiClient client) {
        List<String> names = new ArrayList<>();
        ApiClient.json(client.get("/api/session")).get("roles").forEach(role -> names.add(role.asString()));
        return names;
    }

    private List<JsonNode> entriesOf(ApiClient reader, String action, String grantId) {
        var response = reader.get("/api/audit-entries?size=200&action=" + action);
        assertThat(response.status()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        ApiClient.json(response).get("items").forEach(entry -> {
            if (entry.path("detail").path("grant_id").asString("").equals(grantId)) {
                items.add(entry);
            }
        });
        return items;
    }

    @Test
    void aVaPocRoleLapsesAndComesBackOnlyThroughConfiguration() {
        String grant = arrange.grantsOf(POC_TWO).get(0).get("id").asString();
        clock.set(STARTED.plus(Duration.ofDays(91)));
        ApiClient poc = vaPoc();
        int lapsedBefore = entriesOf(poc, "ROLE_LAPSED", grant).size();

        arrange.roleLapse();

        assertThat(roles(otherVaPoc())).isEmpty();
        assertThat(roles(vaPoc())).as("used this morning").containsExactly("VA_POC");
        List<JsonNode> lapsed = entriesOf(vaPoc(), "ROLE_LAPSED", grant);
        assertThat(lapsed).hasSize(lapsedBefore + 1);
        assertThat(lapsed.getFirst().get("detail").get("role").asString()).isEqualTo("VA_POC");
        assertThat(lapsed.getFirst().get("detail").get("source").asString()).isEqualTo("CONFIG");

        registered(listed("SYS-P1", "Poc one", "p.smo@ci.annualva.test", "p.cm@ci.annualva.test", "Q3"));
        startupSync.sync();
        restartWith(List.of());
        assertThat(roles(otherVaPoc())).as("neither an apply nor a restart naming nobody returns it").isEmpty();

        restartWith(List.of(POC_TWO));
        restartWith(List.of(POC_TWO));

        assertThat(roles(otherVaPoc())).containsExactly("VA_POC");
        List<JsonNode> returned = entriesOf(vaPoc(), "ROLE_RETURNED", grant);
        assertThat(returned.getFirst().get("detail").get("reason").asString()).isEqualTo("configuration");
        assertThat(returned.getFirst().get("actorId").isNull()).as("the deployment is the actor").isTrue();
        assertThat(returned.getFirst().get("occurredAt").asString()).isEqualTo(clock.instant().toString());
    }
}
