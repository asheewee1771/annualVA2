package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Only a VA POC may apply a preview (the permissions matrix), with a fresh sign-in. Everyone else is refused and the
 * refusal changes nothing; a person who is not signed in is unauthenticated, and one without the CSRF token is refused.
 */
class RegisterApplyAccessTest extends RegisterSeam1 {

    private static String applyPath(JsonNode preview) {
        return PREVIEWS + preview.get("id").asString() + "/apply";
    }

    @Test
    void anSmoACyberManagerAnAuditorAndSomeoneWithNoRoleAreForbiddenAndNothingIsApplied() {
        String system = arrange.system();
        arrange.grant("SMO", "apply.smo@ci.annualva.test", system);
        arrange.grant("CYBER_MANAGER", "apply.cm@ci.annualva.test", system);
        arrange.grant("AUDITOR", "apply.auditor@ci.annualva.test", null, "GRANT", null);
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-ACC", "Access", "acc@ci.annualva.test", "acc.cm@ci.annualva.test", "Q1"));

        for (String email : new String[] {"apply.smo@ci.annualva.test", "apply.cm@ci.annualva.test",
            "apply.auditor@ci.annualva.test", "apply.norole@ci.annualva.test"}) {
            var refused = signedInAs(email).post(applyPath(preview));

            assertThat(refused.status()).as(email).isEqualTo(403);
            assertThat(problemType(refused)).isEqualTo("https://annualva/problems/forbidden");
        }

        JsonNode read = ApiClient.json(poc.get(PREVIEWS + preview.get("id").asString()));
        assertThat(read.get("status").asString()).as("still waiting").isEqualTo("PREVIEW");
        assertThat(ApiClient.json(poc.get("/api/systems")).findValuesAsString("systemId")).doesNotContain("SYS-ACC");
    }

    @Test
    void withoutASessionTheApplyIsUnauthenticated() {
        var response = anotherBrowser().post(PREVIEWS + UUID.randomUUID() + "/apply");

        assertThat(response.status()).isIn(401, 403);
    }

    @Test
    void anApplyWithoutTheCsrfTokenIsRefused() {
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-CSRF", "Csrf", "csrf@ci.annualva.test", "csrf.cm@ci.annualva.test", "Q1"));
        ApiClient noToken = anotherBrowser();
        SignIn.signedIn(noToken, issuer, fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));

        var response = noToken.post(applyPath(preview));

        assertThat(response.status()).isEqualTo(403);
        assertThat(problemType(response)).isEqualTo("https://annualva/problems/csrf-rejected");
        assertThat(ApiClient.json(vaPoc().get("/api/systems"))).as("nothing applied").isEmpty();
    }

    @Test
    void eitherVaPocMayApplyAPreviewTheOtherMade() {
        ApiClient first = vaPoc();
        ApiClient second = otherVaPoc();
        JsonNode preview = preview(first, RegisterXlsx.register()
                .row("SYS-EITHER", "Either", "either@ci.annualva.test", "either.cm@ci.annualva.test", "Q2"));

        assertThat(second.post(applyPath(preview)).status()).isEqualTo(200);
    }
}
