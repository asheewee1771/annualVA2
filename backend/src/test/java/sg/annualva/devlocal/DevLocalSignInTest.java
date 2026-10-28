package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 63, ADR 0058: under the dev-local profile a person signs in as one of six personas, through the same sign-in
 * rules as a real sign-in, and holds a role only when a Register a VA POC applied names their email.
 */
class DevLocalSignInTest extends DevLocalSeam1 {

    @Test
    void theSixPersonasAreListedWithTheirNameRoleAndEmailToAnyone() {
        var answer = browser().get("/api/dev/personas");

        assertThat(answer.status()).isEqualTo(200);
        List<String> listed = new ArrayList<>();
        for (JsonNode persona : ApiClient.json(answer)) {
            listed.add(persona.get("id").asString() + "|" + persona.get("name").asString() + "|"
                    + persona.get("roleLabel").asString() + "|" + persona.get("email").asString());
            assertThat(persona.propertyNames()).containsExactlyInAnyOrder("id", "name", "roleLabel", "email");
        }
        assertThat(listed).containsExactly(
                SMO_A + "|SMO A|SMO/Tech Lead|smo.a@dev.annualva.test",
                SMO_B + "|SMO B|SMO/Tech Lead|smo.b@dev.annualva.test",
                CM_A + "|Cyber Manager A|Cyber Manager|cm.a@dev.annualva.test",
                CM_B + "|Cyber Manager B|Cyber Manager|cm.b@dev.annualva.test",
                POC_1 + "|VA POC 1|VA POC|poc.1@dev.annualva.test",
                POC_2 + "|VA POC 2|VA POC|poc.2@dev.annualva.test");
    }

    @Test
    void aVaPocPersonaSignsInAndHoldsTheVaPocRoleBecauseConfigurationNamesThem() {
        ApiClient poc = signedInAs(POC_1);

        JsonNode session = session(poc);
        assertThat(session.get("authenticated").asBoolean()).isTrue();
        assertThat(session.get("email").asString()).isEqualTo("poc.1@dev.annualva.test");
        assertThat(session.get("name").asString()).isEqualTo("VA POC 1");
        assertThat(roles(poc)).containsExactly("VA_POC");
    }

    @Test
    void aPersonaTheRegisterDoesNotNameSeesNoRoleAsInRealLife() {
        registerApplied("DEV-1|Payroll|smo.a@dev.annualva.test|cm.a@dev.annualva.test|Q3");

        assertThat(roles(signedInAs(SMO_B))).isEmpty();
        assertThat(roles(signedInAs(CM_B))).isEmpty();
    }

    @Test
    void anSmoAndACyberManagerPersonaHoldTheirRolesOnlyOnceTheRegisterNamesThem() {
        assertThat(roles(signedInAs(SMO_A))).as("before any Register").isEmpty();
        registerApplied("DEV-1|Payroll|smo.a@dev.annualva.test|cm.a@dev.annualva.test|Q3");

        assertThat(roles(signedInAs(SMO_A))).containsExactly("SMO");
        assertThat(roles(signedInAs(CM_A))).containsExactly("CYBER_MANAGER");
    }

    @Test
    void theSignInBodyCannotGrantARole() {
        ApiClient browser = browser().withCsrf();

        var answer = browser.postJson("/api/dev/sign-in",
                "{\"persona\":\"" + SMO_B + "\",\"roles\":[\"VA_POC\"],\"role\":\"VA_POC\",\"email\":\"poc.1@dev.annualva.test\"}");

        assertThat(answer.status()).isEqualTo(204);
        ApiClient signedIn = browser.withCsrf();
        assertThat(roles(signedIn)).isEmpty();
        assertThat(session(signedIn).get("email").asString()).isEqualTo("smo.b@dev.annualva.test");
        assertThat(signedIn.get("/api/probe/va-poc").status()).isEqualTo(403);
    }

    @Test
    void aPersonaThatIsNotOneOfTheSixIsRefusedAndCreatesNoSession() {
        for (String body : List.of("{\"persona\":\"11111111-0000-4000-8000-0000000000ff\"}",
                "{\"persona\":\"smo.a@dev.annualva.test\"}", "{\"persona\":\"\"}", "{}", "not json", "[]",
                "{\"persona\":7}")) {
            ApiClient browser = browser().withCsrf();

            var answer = browser.postJson("/api/dev/sign-in", body);

            assertThat(answer.status()).as(body).isEqualTo(400);
            assertThat(answer.header("content-type")).startsWith("application/problem+json");
            assertThat(answer.body()).as("nothing the caller sent is echoed").doesNotContain("0000000000ff");
            assertThat(session(browser).get("authenticated").asBoolean()).as(body).isFalse();
        }
    }

    @Test
    void signingInNeedsTheCsrfToken() {
        var answer = browser().postJson("/api/dev/sign-in", "{\"persona\":\"" + POC_1 + "\"}");

        assertThat(answer.status()).isEqualTo(403);
    }

    @Test
    void aSecondSignInOfTheSamePersonaEndsTheFirstSession() {
        ApiClient first = signedInAs(SMO_A);
        assertThat(first.get("/api/probe/whoami").status()).isEqualTo(200);

        ApiClient second = signedInAs(SMO_A);

        assertThat(second.get("/api/probe/whoami").status()).isEqualTo(200);
        assertThat(first.get("/api/probe/whoami").status()).as("the older session ended").isEqualTo(401);
    }

    @Test
    void signingInRotatesTheSessionIdOfABrowserThatAlreadyHadOne() {
        ApiClient browser = browser().withCsrf();
        String before = browser.cookie("SESSION");

        assertThat(signIn(browser, POC_1).status()).isEqualTo(204);

        assertThat(before).isNotBlank();
        assertThat(browser.cookie("SESSION")).isNotBlank().isNotEqualTo(before);
    }

    @Test
    void theSignInIsAuditedLikeAnyOtherAndNamesThePersonByUserId() {
        ApiClient smo = signedInAs(SMO_A);
        String smoId = userId(smo);
        ApiClient poc = signedInAs(POC_1);
        int before = AuditEntries.all(poc, "action=SIGN_IN&actor=" + smoId).size();

        signedInAs(SMO_A);

        List<JsonNode> entries = AuditEntries.all(poc, "action=SIGN_IN&actor=" + smoId);
        assertThat(entries).as("one more sign-in entry").hasSize(before + 1);
        assertThat(entries.toString()).doesNotContain("smo.a@dev.annualva.test");
    }

    @Test
    void theSessionHasTheLimitsOfAnyOther() {
        ApiClient poc = signedInAs(POC_1);

        assertThat(session(poc).get("idleTimeoutSeconds").asLong()).isEqualTo(1800);
        clock.advance(java.time.Duration.ofMinutes(31));
        assertThat(poc.get("/api/probe/whoami").status()).as("idle for 31 minutes").isEqualTo(401);
    }

    @Test
    void signingOutEndsThePersonasSession() {
        ApiClient poc = signedInAs(POC_1);

        assertThat(poc.post("/api/logout").status()).isEqualTo(204);

        assertThat(poc.get("/api/probe/whoami").status()).isEqualTo(401);
    }

    private static String userId(ApiClient browser) {
        return session(browser).get("uuid").asString();
    }
}
