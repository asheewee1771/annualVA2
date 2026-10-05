package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Story 77, IM8 ac-2, ADR 0004: a sensitive command is refused unless the person signed in again
 * within five minutes; the fresh sign-in is a new authorization-code round trip with prompt=login and
 * max_age=0, checked for a fresh auth_time and the same person, and it never extends the session.
 */
@ExtendWith(OutputCaptureExtension.class)
class StepUpTest extends Seam1Test {

    private static final String START = "/auth/step-up?intent=probe-sensitive";

    @Test
    void aSensitiveActionRunsWhileTheSignInIsFresh() {
        signInFresh("af-9001", "fresh@ci.annualva.test");

        assertThat(sensitive().status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(5));
        assertThat(sensitive().status()).as("exactly at the window").isEqualTo(200);
    }

    @Test
    void aSensitiveActionIsRefusedOnceTheSignInIsOlderThanFiveMinutes() {
        signInFresh("af-9002", "stale@ci.annualva.test");

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        var refused = sensitive();

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.header("content-type")).startsWith("application/problem+json");
        JsonNode problem = ApiClient.json(refused);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/step-up-required");
        assertThat(problem.get("status").asInt()).isEqualTo(403);
        assertThat(problem.get("stepUpPath").asString()).isEqualTo(START);
        assertThat(problem.get("traceId").asString()).isNotBlank();
        assertThat(api.get("/api/probe/whoami").status()).as("only the sensitive command is refused").isEqualTo(200);
    }

    @Test
    void aSignInWithAnOldAuthTimeIsNotFreshEvenRightAfterSignIn() {
        // The token is valid and the person is let in, but the provider authenticated them long ago.
        var identity = TestIdentity.of("af-9003", "old.auth@ci.annualva.test", "Old Auth")
                .withAuthTime(clock.instant().minus(Duration.ofHours(2)));
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();

        assertThat(sensitive().status()).isEqualTo(403);
    }

    @Test
    void stepUpStartsAFreshAuthorizationRequestAndRestoresFreshness() {
        var identity = fresh("af-9004", "stepup@ci.annualva.test", "Step Up");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        JsonNode before = ApiClient.json(api.get("/api/session"));
        clock.advance(Duration.ofMinutes(6));
        assertThat(sensitive().status()).isEqualTo(403);

        var flow = SignIn.stepUp(api, issuer, START + "&returnTo=/va-poc", fresh(identity));

        assertThat(flow.authorizationRequest())
                .containsEntry("prompt", "login")
                .containsEntry("max_age", "0")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("response_type", "code")
                .containsEntry("scope", "openid afcas")
                .containsEntry("kc_idp_hint", "singpass")
                .containsKey("nonce")
                .containsKey("state");
        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/va-poc?stepup=done");
        assertThat(issuer.lastTokenRequest().pkceValid()).isTrue();
        assertThat(issuer.lastTokenRequest().clientAssertionValid()).isTrue();

        api.withCsrf();
        assertThat(sensitive().status()).isEqualTo(200);
        JsonNode after = ApiClient.json(api.get("/api/session"));
        assertThat(after.get("authenticated").asBoolean()).isTrue();
        assertThat(after.get("uuid").asString()).isEqualTo(before.get("uuid").asString());
        assertThat(after.get("absoluteExpiresAt").asString())
                .as("a step-up never extends the session")
                .isEqualTo(before.get("absoluteExpiresAt").asString());

        clock.advance(Duration.ofMinutes(6));
        assertThat(sensitive().status()).as("freshness runs out again").isEqualTo(403);
    }

    @Test
    void aStepUpDoesNotExtendTheEightHourLimit() {
        var identity = fresh("af-9005", "absolute.step@ci.annualva.test", "Absolute Step");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        for (int i = 0; i < 16; i++) {
            clock.advance(Duration.ofMinutes(29));
            assertThat(api.get("/api/probe/whoami").status()).isEqualTo(200);
        }

        var flow = SignIn.stepUp(api, issuer, START, fresh(identity));
        assertThat(flow.callback().header("location")).endsWith("/?stepup=done");
        api.withCsrf();
        assertThat(sensitive().status()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(29));
        assertThat(api.get("/api/probe/whoami").status()).as("8 h after the original sign-in").isEqualTo(401);
    }

    @Test
    void aStaleAuthTimeFromTheProviderIsRefusedAndTheSessionStaysNotFresh() {
        var identity = fresh("af-9006", "ignored.prompt@ci.annualva.test", "Ignored Prompt");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));
        assertThat(sensitive().status()).isEqualTo(403);

        // The provider ignored prompt=login and answered with the sign-in from ten minutes ago.
        var flow = SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minus(Duration.ofMinutes(10))));

        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/?stepup=refused&reason=stale-auth-time");
        assertThat(sensitive().status()).as("still not fresh").isEqualTo(403);
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean())
                .as("the existing session is neither ended nor extended by a refused step-up")
                .isTrue();
    }

    @Test
    void aSkewWithinToleranceIsAccepted() {
        var identity = fresh("af-9007", "skew@ci.annualva.test", "Skew");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));

        var tooOld = SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minusSeconds(40)));
        assertThat(tooOld.callback().header("location")).endsWith("/?stepup=refused&reason=stale-auth-time");

        var flow = SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minusSeconds(20)));

        assertThat(flow.callback().header("location")).endsWith("/?stepup=done");
        api.withCsrf();
        assertThat(sensitive().status()).isEqualTo(200);
    }

    @Test
    void aTokenWithoutAuthTimeIsRefused() {
        var identity = fresh("af-9008", "no.auth.time@ci.annualva.test", "No Auth Time");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));

        var flow = SignIn.stepUp(api, issuer, START, identity.withoutAuthTime());

        assertThat(flow.callback().header("location")).endsWith("/?stepup=refused&reason=missing-auth-time");
        assertThat(sensitive().status()).isEqualTo(403);
    }

    @Test
    void aTokenWithoutMultiFactorEvidenceIsRefused() {
        var identity = fresh("af-9009", "no.mfa@ci.annualva.test", "No MFA");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));

        var flow = SignIn.stepUp(api, issuer, START, fresh(identity).withAmr(List.of("pwd")));

        assertThat(flow.callback().header("location")).endsWith("/?stepup=refused&reason=mfa-not-evidenced");
        assertThat(sensitive().status()).isEqualTo(403);
    }

    @Test
    void aFreshSignInByAnotherPersonCannotRefreshThisSession() {
        var colleague = fresh("af-9011", "colleague@ci.annualva.test", "Colleague");
        SignIn.signedIn(anotherBrowser(), issuer, colleague);
        var identity = fresh("af-9010", "hijacked@ci.annualva.test", "Hijacked");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));

        var flow = SignIn.stepUp(api, issuer, START, fresh(colleague));

        assertThat(flow.callback().header("location")).endsWith("/?stepup=refused&reason=identity-mismatch");
        assertThat(sensitive().status()).isEqualTo(403);
        assertThat(ApiClient.json(api.get("/api/session")).get("email").asString())
                .as("the session still belongs to the same person")
                .isEqualTo("hijacked@ci.annualva.test");
    }

    @Test
    void aStepUpCannotBeStartedWithoutASession() {
        var response = api.get(START);

        assertThat(response.status()).isEqualTo(401);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/unauthenticated");
        assertThat(issuer.lastAuthorizationRequest()).doesNotContainEntry("max_age", "0");
    }

    @Test
    void anIntentThatIsNotACodeIsABadRequest() {
        SignIn.signedIn(api, issuer, fresh("af-9012", "intent@ci.annualva.test", "Intent"));

        assertThat(api.get("/auth/step-up?intent=Register%20Apply").status()).isEqualTo(400);
        assertThat(api.get("/auth/step-up?intent=").status()).isEqualTo(400);
        assertThat(api.get("/auth/step-up").status()).isEqualTo(400);
        assertThat(api.get("/auth/step-up?intent=a%0d%0ab").status()).isEqualTo(400);
    }

    @Test
    void theReturnPathIsNeverAnotherSite() {
        var identity = fresh("af-9013", "return@ci.annualva.test", "Return");
        SignIn.signedIn(api, issuer, identity);

        for (String hostile : List.of("https://evil.example/x", "//evil.example", "/\\evil.example", "/a?b=c", "javascript:alert(1)")) {
            var flow = SignIn.stepUp(api, issuer, START + "&returnTo=" + java.net.URLEncoder.encode(hostile,
                    java.nio.charset.StandardCharsets.UTF_8), fresh(identity));
            assertThat(flow.callback().header("location")).as(hostile).endsWith("/?stepup=done").doesNotContain("evil");
        }
    }

    @Test
    void successfulAndRefusedStepUpsAreAuditedWithTheUserIdAndNeverTheEmail(CapturedOutput output) {
        var identity = fresh("af-9014", "audited@ci.annualva.test", "Audited Person");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        String userId = ApiClient.json(api.get("/api/session")).get("uuid").asString();
        clock.advance(Duration.ofMinutes(10));
        sensitive();

        SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minus(Duration.ofMinutes(10))));
        SignIn.stepUp(api, issuer, START, fresh(identity));

        List<String> events = output.getAll().lines().filter(line -> line.contains(userId)).toList();
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"request-refused\"").contains("\"reason\":\"step-up-required\"")
                .contains("probe-sensitive"));
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"step-up-refused\"").contains("\"reason\":\"stale-auth-time\"")
                .contains("\"outcome\":\"failure\"").contains("probe-sensitive"));
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"step-up\"").contains("\"outcome\":\"success\"").contains("probe-sensitive"));
        assertThat(output.getAll()).doesNotContain("audited@ci.annualva.test").doesNotContain("Audited Person");
    }

    @Test
    void theSensitiveCommandIsNotReplayedByTheStepUp() {
        var identity = fresh("af-9015", "no.replay@ci.annualva.test", "No Replay");
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));
        assertThat(sensitive().status()).isEqualTo(403);

        var flow = SignIn.stepUp(api, issuer, START, fresh(identity));

        assertThat(flow.callback().status()).as("a redirect to the screen, not a result").isEqualTo(302);
        assertThat(flow.callback().body()).isEmpty();
    }

    private void signInFresh(String ssoId, String email) {
        SignIn.signedIn(api, issuer, fresh(ssoId, email, "Person " + ssoId));
        api.withCsrf();
    }

    /** A well-formed MFA sign-in whose auth_time is the application clock's now. */
    private TestIdentity fresh(String ssoId, String email, String name) {
        return fresh(TestIdentity.of(ssoId, email, name));
    }

    private TestIdentity fresh(TestIdentity identity) {
        return identity.withAuthTime(clock.instant());
    }

    private ApiClient.Response sensitive() {
        return api.post("/api/probe/sensitive");
    }
}
