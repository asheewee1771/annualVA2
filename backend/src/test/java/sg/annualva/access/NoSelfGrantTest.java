package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * Story 76: nobody can give themselves, or anyone, a role. Roles change only by a Register apply,
 * configuration and (later) the role-holder review, so there is no request a signed-in person can
 * send that grants a role, and nothing the identity provider's token says counts as one.
 */
class NoSelfGrantTest extends Seam1Test {

    private static List<String> roles(ApiClient api) {
        var names = new ArrayList<String>();
        ApiClient.json(api.get("/api/session")).get("roles").forEach(role -> names.add(role.asString()));
        return names;
    }

    @Test
    void claimsInTheTokenNeverGrantARole() {
        var identity = TestIdentity.of("af-7001", "claims@ci.annualva.test", "Claims")
                .withClaim("roles", List.of("VA_POC", "CYBER_MANAGER"))
                .withClaim("groups", List.of("VA_POC"))
                .withClaim("role", "VA_POC")
                .withClaim("afcas.roles", List.of("VA_POC"))
                .withClaim("realm_access", Map.of("roles", List.of("VA_POC", "admin")));

        SignIn.signedIn(api, issuer, identity);

        assertThat(roles(api)).isEmpty();
        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(403);
        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(403);
    }

    @Test
    void thereIsNoEndpointThroughWhichAPersonCanGrantARole() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-7002", "attempts@ci.annualva.test", "Attempts"));
        api.withCsrf();
        String self = ApiClient.json(api.get("/api/session")).get("uuid").asString();
        String body = "{\"role\":\"VA_POC\",\"email\":\"attempts@ci.annualva.test\",\"userId\":\"" + self + "\"}";

        for (String path : List.of("/api/roles", "/api/grants", "/api/role-grants", "/api/users/" + self + "/roles",
                "/api/session/roles", "/api/session", "/api/auditors", "/api/admin/roles")) {
            int status = api.postJson(path, body).status();
            assertThat(status).as("POST " + path).isIn(403, 404, 405);
        }
        assertThat(roles(api)).isEmpty();
        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(403);
    }

    @Test
    void anUnverifiedEmailCannotPickUpAGrantMadeForSomeoneElse() {
        String victim = "victim@ci.annualva.test";
        arrange.grant("CYBER_MANAGER", victim, arrange.system());

        var attacker = TestIdentity.of("af-7003", victim, "Attacker").withEmailVerified(false);
        var flow = SignIn.through(api, issuer, attacker);

        assertThat(flow.callback().header("location")).contains("signin=refused");
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
        assertThat(arrange.grantsOf(victim).get(0).get("attached").asBoolean()).isFalse();
    }
}
