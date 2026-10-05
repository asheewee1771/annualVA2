package sg.annualva.adapters.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * A stalled identity provider ends the sign-in with a provider-error refusal once the read timeout
 * (2 s in the ci profile) passes, instead of holding a request thread.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProviderTimeoutTest extends Seam1Test {

    private static final Duration STALL = Duration.ofSeconds(5);

    @AfterEach
    void providerRecovers() {
        issuer.stallTokenEndpoint(Duration.ZERO);
        issuer.stallJwksEndpoint(Duration.ZERO);
    }

    @Test
    void stalledTokenEndpointIsRefusedAsAProviderError(CapturedOutput output) {
        issuer.stallTokenEndpoint(STALL);
        assertTimedOutRefusal(TestIdentity.of("af-6001", "slow.token@ci.annualva.test", "Slow Token"), output);
    }

    @Test
    void stalledJwksEndpointIsRefusedAsAProviderError(CapturedOutput output) {
        issuer.stallJwksEndpoint(STALL);
        assertTimedOutRefusal(TestIdentity.of("af-6002", "slow.jwks@ci.annualva.test", "Slow Jwks"), output);
    }

    private void assertTimedOutRefusal(TestIdentity identity, CapturedOutput output) {
        long started = System.nanoTime();
        var flow = SignIn.through(api, issuer, identity);
        Duration waited = Duration.ofNanos(System.nanoTime() - started);

        assertThat(waited).as("the call gave up at the read timeout, not when the provider answered").isLessThan(STALL);
        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/?signin=refused&reason=provider-error");
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
        assertThat(api.get("/api/probe/whoami").status()).isEqualTo(401);
        assertThat(output.getAll())
                .contains("\"action\":\"sign-in-refused\"")
                .contains("\"reason\":\"provider-error\"")
                .doesNotContain(identity.email());
    }
}
