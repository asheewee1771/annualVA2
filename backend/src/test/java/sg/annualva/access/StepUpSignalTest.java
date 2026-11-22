package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.annualva.harness.Metrics.STEP_UP_FAILED;
import static sg.annualva.harness.Metrics.STEP_UP_REQUIRED;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): a step-up refused is counted, both when a sensitive action comes without a fresh
 * sign-in and when a step-up round trip does not prove one.
 */
class StepUpSignalTest extends Seam1Test {

    private static final String START = "/auth/step-up?intent=probe-sensitive";

    @Test
    void aSensitiveActionWithoutAFreshSignInIsCountedAsRequiredAndAFreshOneIsNot() {
        var identity = TestIdentity.of("af-5301", "stepup.signal@ci.annualva.test", "Step Signal")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        Metrics before = metrics();

        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(200);
        assertThat(metrics().since(before, STEP_UP_REQUIRED)).as("a fresh sign-in is not a refusal").isZero();
        clock.advance(Duration.ofMinutes(6));
        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(403);

        assertThat(metrics().since(before, STEP_UP_REQUIRED)).isEqualTo(1);
    }

    @Test
    void aStepUpRoundTripThatDoesNotProveAFreshSignInIsCountedAsFailed() {
        var identity = TestIdentity.of("af-5302", "stepup.failed@ci.annualva.test", "Step Failed")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));
        Metrics before = metrics();

        // The provider ignored prompt=login and answered with the sign-in from ten minutes ago.
        var flow = SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minus(Duration.ofMinutes(10))));

        assertThat(flow.callback().header("location")).contains("stepup=refused");
        assertThat(metrics().since(before, STEP_UP_FAILED)).isEqualTo(1);
    }
}
