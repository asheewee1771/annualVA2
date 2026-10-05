package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** IM8 ac-1: a method-level rule opens an endpoint only to the roles in the permission matrix. */
class MethodAuthorisationTest extends Seam1Test {

    @Test
    void noSessionIsUnauthenticated() {
        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(401);
    }

    @Test
    void aSignedInPersonWithoutTheRoleIsForbiddenWithAProblemBody() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-8001", "norole@ci.annualva.test", "No Role"));

        var response = api.get("/api/probe/va-poc");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        var body = ApiClient.json(response);
        assertThat(body.get("type").asString()).isEqualTo("https://annualva/problems/forbidden");
        assertThat(body.get("traceId").asString()).isEqualTo(response.header("X-Correlation-Id"));
    }

    @Test
    void aDifferentRoleDoesNotOpenIt() {
        arrange.grant("CYBER_MANAGER", "wrongrole@ci.annualva.test", arrange.system());
        SignIn.signedIn(api, issuer, TestIdentity.of("af-8002", "wrongrole@ci.annualva.test", "Wrong Role"));

        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(403);
        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(200);
    }

    @Test
    void theRightRoleOpensIt() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-5001", "poc.one@ci.annualva.test", "POC One"));

        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(200);
        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(403);
    }
}
