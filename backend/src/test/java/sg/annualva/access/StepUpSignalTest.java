package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): a step-up refused is counted, both when a sensitive action comes without a fresh
 * sign-in and when a step-up round trip does not prove one.
 */
class StepUpSignalTest extends Seam1Test {

    private static final String REQUIRED = "annualva_stepup_refusals_total{kind=\"required\"}";
    private static final String FAILED = "annualva_stepup_refusals_total{kind=\"failed\"}";
    private static final String START = "/auth/step-up?intent=probe-sensitive";

    @Test
    void aSensitiveActionWithoutAFreshSignInIsCountedAsRequiredAndAFreshOneIsNot() {
        var identity = TestIdentity.of("af-5301", "stepup.signal@ci.annualva.test", "Step Signal")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        double before = metrics().value(REQUIRED);

        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(200);
        assertThat(metrics().value(REQUIRED)).as("a fresh sign-in is not a refusal").isEqualTo(before);
        clock.advance(Duration.ofMinutes(6));
        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(403);

        assertThat(metrics().value(REQUIRED)).isEqualTo(before + 1);
    }

    @Test
    void aStepUpRoundTripThatDoesNotProveAFreshSignInIsCountedAsFailed() {
        var identity = TestIdentity.of("af-5302", "stepup.failed@ci.annualva.test", "Step Failed")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));
        double before = metrics().value(FAILED);

        // The provider ignored prompt=login and answered with the sign-in from ten minutes ago.
        var flow = SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minus(Duration.ofMinutes(10))));

        assertThat(flow.callback().header("location")).contains("stepup=refused");
        assertThat(metrics().value(FAILED)).isEqualTo(before + 1);
    }
}
