package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** Stories 75 and 76 and IM8 ac-1/ac-3: roles live in the app's role store and are resolved on every request. */
class RoleResolutionTest extends Seam1Test {

    private static final Instant START = Instant.parse("2027-09-06T01:00:00Z");

    @BeforeEach
    void startOfDay() {
        clock.set(START);
    }

    private static String roles(ApiClient api) {
        var roles = ApiClient.json(api.get("/api/session")).get("roles");
        var names = new java.util.ArrayList<String>();
        roles.forEach(role -> names.add(role.asString()));
        return String.join(",", names);
    }

    @Test
    void aPersonWithNoGrantHoldsNoRole() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4001", "nobody@ci.annualva.test", "Nobody"));

        assertThat(roles(api)).isEmpty();
    }

    @Test
    void rolesAreListedHighestPrivilegeFirstAndFollowTheStoreWithoutANewSignIn() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4002", "both@ci.annualva.test", "Both"));
        assertThat(roles(api)).isEmpty();

        String smoSystem = arrange.system();
        String cmSystem = arrange.system();
        arrange.grant("SMO", "both@ci.annualva.test", smoSystem);
        assertThat(roles(api)).as("a grant made after sign-in counts on the next call").isEqualTo("SMO");

        arrange.grant("CYBER_MANAGER", "both@ci.annualva.test", cmSystem);
        assertThat(roles(api)).isEqualTo("CYBER_MANAGER,SMO");

        arrange.grant("SMO", "both@ci.annualva.test", cmSystem);
        assertThat(roles(api)).as("a role held for several Systems is listed once").isEqualTo("CYBER_MANAGER,SMO");
    }

    @Test
    void aRevokedGrantTakesEffectOnTheNextCall() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4003", "revoked@ci.annualva.test", "Revoked"));
        String grant = arrange.grant("CYBER_MANAGER", "revoked@ci.annualva.test", arrange.system());
        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(200);

        arrange.revoke(grant);

        assertThat(api.get("/api/probe/cyber-manager").status()).as("same session, next call").isEqualTo(403);
        assertThat(roles(api)).isEmpty();
    }

    @Test
    void aSuspendedGrantGivesNoAuthority() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4004", "suspended@ci.annualva.test", "Suspended"));
        String grant = arrange.grant("CYBER_MANAGER", "suspended@ci.annualva.test", arrange.system());
        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(200);

        arrange.suspend(grant);

        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(403);
        assertThat(roles(api)).isEmpty();
    }

    @Test
    void aLapsedGrantGivesNoAuthority() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4005", "lapsed@ci.annualva.test", "Lapsed"));
        String grant = arrange.grant("CYBER_MANAGER", "lapsed@ci.annualva.test", arrange.system());
        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(200);

        arrange.lapse(grant);

        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(403);
        assertThat(roles(api)).isEmpty();
    }

    @Test
    void anExpiredGrantGivesNoAuthority() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4006", "expiring@ci.annualva.test", "Expiring"));
        arrange.grant("AUDITOR", "expiring@ci.annualva.test", null, "GRANT", START.plus(Duration.ofMinutes(10)).toString());
        assertThat(roles(api)).isEqualTo("AUDITOR");

        clock.advance(Duration.ofMinutes(9));
        assertThat(roles(api)).as("still inside its time limit").isEqualTo("AUDITOR");
        clock.advance(Duration.ofMinutes(2));
        assertThat(roles(api)).as("past its time limit, same session").isEmpty();
    }

    @Test
    void aGrantMadeByEmailBeforeTheFirstSignInAttachesAtThatSignIn() {
        arrange.grant("CYBER_MANAGER", "Early.Bird@ci.annualva.test", arrange.system());
        var before = arrange.grantsOf("early.bird@ci.annualva.test");
        assertThat(before.size()).isEqualTo(1);
        assertThat(before.get(0).get("attached").asBoolean()).as("nobody to attach to yet").isFalse();

        SignIn.signedIn(api, issuer, TestIdentity.of("af-4007", "early.bird@ci.annualva.test", "Early Bird"));

        assertThat(roles(api)).isEqualTo("CYBER_MANAGER");
        assertThat(arrange.grantsOf("early.bird@ci.annualva.test").get(0).get("attached").asBoolean()).isTrue();
    }

    @Test
    void aGrantForAPreRegisteredPersonIsAttachedAtOnceAndSurvivesTheirFirstSignIn() {
        String preRegistered = arrange.preRegister("known@ci.annualva.test");
        arrange.grant("SMO", "known@ci.annualva.test", arrange.system());
        assertThat(arrange.grantsOf("known@ci.annualva.test").get(0).get("attached").asBoolean())
                .as("the person is already known by email").isTrue();

        SignIn.signedIn(api, issuer, TestIdentity.of("af-4008", "known@ci.annualva.test", "Known"));

        assertThat(ApiClient.json(api.get("/api/session")).get("uuid").asString()).isEqualTo(preRegistered);
        assertThat(roles(api)).isEqualTo("SMO");
    }

    @Test
    void aSecondPersonClaimingTheSameEmailDoesNotInheritTheGrant() {
        arrange.grant("CYBER_MANAGER", "shared@ci.annualva.test", arrange.system());
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4009", "shared@ci.annualva.test", "First Holder"));
        assertThat(roles(api)).isEqualTo("CYBER_MANAGER");

        // The first holder's email moves on; a different person later signs in with the old address.
        api.withCsrf().post("/api/logout");
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4009", "first.moved@ci.annualva.test", "First Holder"));
        assertThat(roles(api)).as("the grant follows the person, not the address").isEqualTo("CYBER_MANAGER");

        var stranger = anotherBrowser();
        SignIn.signedIn(stranger, issuer, TestIdentity.of("af-4010", "shared@ci.annualva.test", "Stranger"));
        assertThat(roles(stranger)).as("the grant is already attached to someone else").isEmpty();
    }

    @Test
    void grantsAreMarkedUsedAtMostOncePerDay() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4011", "usage@ci.annualva.test", "Usage"));
        arrange.grant("CYBER_MANAGER", "usage@ci.annualva.test", arrange.system());
        assertThat(arrange.grantsOf("usage@ci.annualva.test").get(0).get("lastUsedAt").isNull()).isTrue();

        assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(200);
        String firstUse = arrange.grantsOf("usage@ci.annualva.test").get(0).get("lastUsedAt").asString();
        assertThat(firstUse).isEqualTo(START.toString());

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofMinutes(25));
            assertThat(api.get("/api/probe/cyber-manager").status()).isEqualTo(200);
        }
        assertThat(arrange.grantsOf("usage@ci.annualva.test").get(0).get("lastUsedAt").asString())
                .as("not rewritten within the same day").isEqualTo(firstUse);

        clock.advance(Duration.ofHours(25));
        var nextDay = anotherBrowser();
        SignIn.signedIn(nextDay, issuer, TestIdentity.of("af-4011", "usage@ci.annualva.test", "Usage"));
        assertThat(nextDay.get("/api/probe/cyber-manager").status()).isEqualTo(200);
        assertThat(arrange.grantsOf("usage@ci.annualva.test").get(0).get("lastUsedAt").asString())
                .isEqualTo(START.plus(Duration.ofMinutes(100)).plus(Duration.ofHours(25)).toString());
    }
}
