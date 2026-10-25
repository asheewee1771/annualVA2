package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import sg.annualva.register.RoleHolderSeam1;
import tools.jackson.databind.JsonNode;

/**
 * Story 80a and IM8 ac-3 (ticket 47): a person the identity provider reports disabled loses access at once. The sign-in
 * (or step-up) callback sees it in the token, and a session check asks the provider at most every five minutes; either
 * way every grant of the person is suspended and every session of theirs ends. A VA POC restores a grant only once the
 * provider lets the person sign in again.
 */
class IdentityProviderDisablementTest extends RoleHolderSeam1 {

    private static final String SUSPENDED = "ROLE_SUSPENDED";

    /** A sign-in of this person whose token says the identity provider has disabled them. */
    private TestIdentity disabled(String email) {
        return fresh(TestIdentity.of(ssoIdOf(email), email, "Person")).withClaim("afcas.status", "DISABLED");
    }

    private void assertEverySmoGrantSuspendedByTheIdentityProvider(String email) {
        ApiClient poc = vaPoc();
        JsonNode review = review(poc);
        for (String system : new String[] {"SYS-HA", "SYS-HB"}) {
            JsonNode holder = holder(review, "SMO", email, system);
            assertThat(holder.get("state").asString()).isEqualTo("SUSPENDED");
            assertThat(holder.get("suspendedBecause").asString()).isEqualTo("IDENTITY_PROVIDER");
            assertThat(holder.get("disabledAtIdentityProvider").asBoolean()).isTrue();
            JsonNode entry = entries(poc, SUSPENDED, holder.get("grantId").asString()).getFirst();
            assertThat(entry.get("detail").get("reason").asString()).isEqualTo("identity-provider");
            assertThat(entry.get("actorId").isNull()).as("the provider's word, not a person's act").isTrue();
        }
    }

    private void bothSystemsNamingSmoA() {
        registered(listed("SYS-HA", "Holder A", SMO_A, CM, "Q3"), listed("SYS-HB", "Holder B", SMO_A, CM, "Q4"));
    }

    @Test
    void aSignInThatShowsThePersonDisabledIsRefusedAndEndsEveryAccessOfTheirs() {
        bothSystemsNamingSmoA();
        ApiClient smo = signedInAs(SMO_A);
        assertThat(smo.get("/api/systems").status()).isEqualTo(200);

        var flow = SignIn.through(anotherBrowser(), issuer, disabled(SMO_A));

        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/?signin=refused&reason=account-disabled");
        assertThat(smo.get("/api/systems").status()).as("the open session ended too").isEqualTo(401);
        assertEverySmoGrantSuspendedByTheIdentityProvider(SMO_A);
        var refusals = vaPoc().get("/api/audit-entries?size=1&action=SIGN_IN_FAILED");
        assertThat(ApiClient.json(refusals).get("items").get(0).get("detail").get("reason").asString())
                .isEqualTo("account-disabled");
    }

    @Test
    void aSessionCheckThatFindsThePersonDisabledEndsTheirSessionAndSuspendsEveryGrant() {
        bothSystemsNamingSmoA();
        ApiClient smo = signedInAs(SMO_A);
        identityStatus.disable(ssoIdOf(SMO_A));
        clock.advance(Duration.ofMinutes(4));
        assertThat(smo.get("/api/systems").status()).as("checked at sign-in, four minutes ago").isEqualTo(200);
        clock.advance(Duration.ofMinutes(1));

        assertThat(smo.get("/api/systems").status()).isEqualTo(401);

        assertThat(smo.get("/api/systems").status()).isEqualTo(401);
        assertEverySmoGrantSuspendedByTheIdentityProvider(SMO_A);
    }

    @Test
    void aSessionCheckTheIdentityProviderCannotAnswerLeavesTheSessionAsItWas() {
        bothSystemsNamingSmoA();
        ApiClient smo = signedInAs(SMO_A);
        identityStatus.unreachable();
        clock.advance(Duration.ofMinutes(6));

        assertThat(smo.get("/api/systems").status()).isEqualTo(200);

        assertThat(roles(smo)).containsExactly("SMO");
    }

    @Test
    void aSuspendedGrantIsRestoredOnlyOnceTheIdentityProviderLetsThePersonSignInAgain() {
        bothSystemsNamingSmoA();
        SignIn.through(anotherBrowser(), issuer, disabled(SMO_A));
        ApiClient poc = vaPoc();
        String grant = grantOf(review(poc), "SMO", SMO_A, "SYS-HA");

        var refused = restore(poc, grant);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/disabled-at-identity-provider");

        ApiClient smo = signedInAs(SMO_A);
        assertThat(roles(smo)).as("enabled again, but still suspended").isEmpty();
        assertThat(holder(review(poc), "SMO", SMO_A, "SYS-HA").get("disabledAtIdentityProvider").asBoolean()).isFalse();
        assertThat(restore(poc, grant).status()).isEqualTo(200);
        assertThat(roles(smo)).containsExactly("SMO");
    }

    @Test
    void aStepUpThatShowsTheVaPocDisabledEndsTheirSessionAndSuspendsTheirRole() {
        twoSystems();
        ApiClient poc = vaPoc();
        TestIdentity disabledPoc = fresh(TestIdentity.of("af-5001", POC_ONE, "POC One"))
                .withClaim("afcas.status", "inactive");

        var flow = SignIn.stepUp(poc, issuer, "/auth/step-up?intent=role-suspend&returnTo=/va-poc", disabledPoc);

        assertThat(flow.callback().header("location")).contains("stepup=refused").contains("reason=account-disabled");
        assertThat(poc.get(HOLDERS).status()).isEqualTo(401);
        JsonNode holder = holder(review(otherVaPoc()), "VA_POC", POC_ONE, null);
        assertThat(holder.get("state").asString()).isEqualTo("SUSPENDED");
        assertThat(holder.get("suspendedBecause").asString()).isEqualTo("IDENTITY_PROVIDER");
    }
}
