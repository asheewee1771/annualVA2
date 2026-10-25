package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * IM8 ac-2 and ac-4 (ticket 47): the role-holder review's only change is suspending a person's role for a System, with a
 * fresh sign-in, until a Register upload names someone else; a VA POC may lift a suspension the same way. Neither may be
 * done to the caller's own grant, and every change and refusal is audited.
 */
class RoleSuspensionTest extends RoleHolderSeam1 {

    private static final String SUSPENDED = "ROLE_SUSPENDED";
    private static final String RESTORED = "ROLE_RESTORED";

    @Test
    void suspendingNeedsAFreshSignInAndChangesNothingWithoutOne() {
        twoSystems();
        ApiClient poc = vaPoc();
        String grant = grantOf(review(poc), "SMO", SMO_A, "SYS-HA");
        clock.advance(Duration.ofMinutes(6));

        var refused = suspend(poc, grant);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/step-up-required");
        assertThat(ApiClient.json(refused).get("stepUpPath").asString()).isEqualTo("/auth/step-up?intent=role-suspend");
        assertThat(holder(review(poc), "SMO", SMO_A, "SYS-HA").get("state").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void aSuspendedRoleStopsCountingForThatSystemAtOnce() {
        registered(listed("SYS-HA", "Holder A", SMO_A, CM, "Q3"), listed("SYS-HB", "Holder B", SMO_A, CM, "Q4"));
        ApiClient smo = signedInAs(SMO_A);
        ApiClient poc = vaPoc();
        String grant = grantOf(review(poc), "SMO", SMO_A, "SYS-HA");

        var response = suspend(poc, grant);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode suspended = ApiClient.json(response);
        assertThat(suspended.get("state").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("suspensionCause").asString()).isEqualTo("REVIEW");
        assertThat(suspended.get("systemCode").asString()).isEqualTo("SYS-HA");
        JsonNode systems = ApiClient.json(smo.get("/api/systems"));
        assertThat(systems).as("SYS-HB only, on the very next request").hasSize(1);
        assertThat(systems.get(0).get("systemId").asString()).isEqualTo("SYS-HB");
        assertThat(roles(smo)).containsExactly("SMO");

        JsonNode entry = entries(poc, SUSPENDED, grant).getFirst();
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("review");
        assertThat(entry.get("detail").get("role").asString()).isEqualTo("SMO");
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(entry.toString()).doesNotContain(SMO_A);
    }

    @Test
    void aSuspensionStandsUntilARegisterUploadNamesSomeoneElse() {
        twoSystems();
        ApiClient poc = vaPoc();
        String grant = grantOf(review(poc), "SMO", SMO_A, "SYS-HA");
        assertThat(suspend(poc, grant).status()).isEqualTo(200);

        twoSystems();
        assertThat(holder(review(vaPoc()), "SMO", SMO_A, "SYS-HA").get("state").asString())
                .as("the Register still names the same person").isEqualTo("SUSPENDED");
        assertThat(roles(signedInAs(SMO_A))).isEmpty();

        registered(listed("SYS-HA", "Holder A", SMO_C, CM, "Q3"), listed("SYS-HB", "Holder B", SMO_B, CM, "Q4"));

        JsonNode review = review(vaPoc());
        assertThat(holder(review, "SMO", SMO_C, "SYS-HA").get("state").asString()).isEqualTo("ACTIVE");
        assertThat(roles(signedInAs(SMO_C))).containsExactly("SMO");
        assertThat(review.get("holders").toString()).as("the suspended grant was revoked").doesNotContain(grant);
    }

    @Test
    void aSuspendedRoleDoesNotComeBackByItselfFromALapse() {
        twoSystems();
        ApiClient poc = vaPoc();
        String grant = grantOf(review(poc), "SMO", SMO_A, "SYS-HA");
        assertThat(suspend(poc, grant).status()).isEqualTo(200);
        arrange.lapse(grant);

        twoSystems();

        ApiClient again = vaPoc();
        JsonNode review = review(again);
        assertThat(holder(review, "SMO", SMO_A, "SYS-HA").get("returnedAt").isNull()).isTrue();
        assertThat(review.get("automaticReturns").toString()).doesNotContain(grant);
        assertThat(ApiClient.json(restore(otherVaPoc(), grant)).get("state").asString())
                .as("restored, and still lapsed until something returns it").isEqualTo("LAPSED");
    }

    @Test
    void aVaPocLiftsASuspensionWithAFreshSignIn() {
        twoSystems();
        ApiClient poc = vaPoc();
        String grant = grantOf(review(poc), "CYBER_MANAGER", CM, "SYS-HB");
        assertThat(suspend(poc, grant).status()).isEqualTo(200);
        assertThat(roles(signedInAs(CM))).as("still the Cyber Manager of SYS-HA").containsExactly("CYBER_MANAGER");
        clock.advance(Duration.ofMinutes(6));
        ApiClient stale = poc;
        assertThat(ApiClient.json(restore(stale, grant)).get("stepUpPath").asString())
                .isEqualTo("/auth/step-up?intent=role-restore");

        var response = restore(otherVaPoc(), grant);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(ApiClient.json(response).get("state").asString()).isEqualTo("ACTIVE");
        assertThat(ApiClient.json(response).get("suspendedAt").isNull()).isTrue();
        ApiClient cm = signedInAs(CM);
        assertThat(ApiClient.json(cm.get("/api/systems"))).hasSize(2);
        JsonNode entry = entries(otherVaPoc(), RESTORED, grant).getFirst();
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("detail").get("grant_id").asString()).isEqualTo(grant);
    }

    @Test
    void aVaPocCannotSuspendOrRestoreTheirOwnGrant() {
        registered(listed("SYS-HA", "Holder A", POC_ONE, CM, "Q3"));
        ApiClient poc = vaPoc();
        JsonNode review = review(poc);
        String own = grantOf(review, "SMO", POC_ONE, "SYS-HA");
        assertThat(holder(review, "SMO", POC_ONE, "SYS-HA").get("own").asBoolean()).isTrue();

        var refused = suspend(poc, own);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(problemType(refused)).isEqualTo("https://annualva/problems/own-grant");
        assertThat(holder(review(poc), "SMO", POC_ONE, "SYS-HA").get("state").asString()).isEqualTo("ACTIVE");
        JsonNode entry = entries(poc, SUSPENDED, own).getFirst();
        assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("own-grant");

        assertThat(suspend(otherVaPoc(), own).status()).as("the other VA POC may").isEqualTo(200);
        ApiClient again = vaPoc();
        assertThat(restore(again, own).status()).isEqualTo(403);
        String ownVaPoc = grantOf(review(again), "VA_POC", POC_ONE, null);
        assertThat(suspend(again, ownVaPoc).status()).isEqualTo(403);
    }

    @Test
    void onlyARoleHeldForASystemCanBeSuspendedAndOnlyOnce() {
        twoSystems();
        ApiClient poc = vaPoc();
        JsonNode review = review(poc);
        String otherVaPocGrant = grantOf(review, "VA_POC", POC_TWO, null);
        String grant = grantOf(review, "SMO", SMO_B, "SYS-HB");

        var vaPocRole = suspend(poc, otherVaPocGrant);
        assertThat(vaPocRole.status()).isEqualTo(409);
        assertThat(problemType(vaPocRole)).isEqualTo("https://annualva/problems/not-for-a-system");
        assertThat(restore(poc, grant).status()).as("not suspended").isEqualTo(409);
        assertThat(suspend(poc, grant).status()).isEqualTo(200);
        var again = suspend(poc, grant);
        assertThat(again.status()).isEqualTo(409);
        assertThat(problemType(again)).isEqualTo("https://annualva/problems/already-suspended");
        assertThat(suspend(poc, UUID.randomUUID().toString()).status()).isEqualTo(404);
        List<JsonNode> audited = entries(poc, SUSPENDED, grant);
        assertThat(audited).extracting(entry -> entry.get("outcome").asString()).containsExactly("REFUSED", "SUCCESS");
    }

    @Test
    void onlyAVaPocSuspendsOrRestores() {
        twoSystems();
        String grant = grantOf(review(vaPoc()), "SMO", SMO_B, "SYS-HB");
        ApiClient cm = signedInAs(CM);

        assertThat(suspend(cm, grant).status()).isEqualTo(403);
        assertThat(restore(cm, grant).status()).isEqualTo(403);
    }
}
