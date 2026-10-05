package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * ADR 0004's fallback: when the identity provider ignores prompt=login, the same component ends the
 * session and makes the person sign in from the start (configured as {@code annualva.step-up.mode}).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "annualva.step-up.mode=full-sign-in")
class StepUpFullSignInTest extends Seam1Test {

    @Test
    void stepUpEndsTheSessionAndSendsThePersonToSignInFromTheStart(CapturedOutput output) {
        var identity = TestIdentity.of("af-9101", "full.sign.in@ci.annualva.test", "Full Sign In")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(6));
        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(403);
        String userId = ApiClient.json(api.get("/api/session")).get("uuid").asString();

        var started = api.get("/auth/step-up?intent=probe-sensitive&returnTo=/va-poc");

        assertThat(started.status()).isEqualTo(302);
        assertThat(started.header("location")).endsWith("/oauth2/authorization/mcc-sso-login");
        assertThat(api.get("/api/probe/whoami").status()).as("the session is over").isEqualTo(401);
        assertThat(output.getAll().lines().filter(line -> line.contains(userId)).toList())
                .anySatisfy(line -> assertThat(line).contains("\"action\":\"step-up-full-sign-in\""));

        SignIn.signedIn(api, issuer, identity.withAuthTime(clock.instant()));
        api.withCsrf();
        assertThat(issuer.lastAuthorizationRequest()).doesNotContainKey("prompt");
        assertThat(api.post("/api/probe/sensitive").status()).as("the full sign-in is the fresh one").isEqualTo(200);

        var reader = anotherBrowser();
        SignIn.signedIn(reader, issuer, TestIdentity.of("af-5002", "poc.two@ci.annualva.test", "POC Two"));
        var audited = ApiClient.json(reader.get("/api/audit-entries?action=STEP_UP_FULL_SIGN_IN&actor=" + userId));
        assertThat(audited.get("total").asInt()).isEqualTo(1);
        assertThat(audited.get("items").get(0).get("detail").get("intent").asString()).isEqualTo("probe-sensitive");
    }
}
