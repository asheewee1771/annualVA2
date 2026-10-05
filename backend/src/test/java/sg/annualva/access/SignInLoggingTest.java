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

/** Sign-in, sign-out and refused sign-ins are ECS events that name the user id and never the email or token. */
@ExtendWith(OutputCaptureExtension.class)
class SignInLoggingTest extends Seam1Test {

    @Test
    void signInAndSignOutAreLoggedWithTheUserIdOnly(CapturedOutput output) {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4001", "logged@ci.annualva.test", "Logged Person"));
        String userId = ApiClient.json(api.get("/api/session")).get("uuid").asString();
        api.withCsrf().post("/api/logout");

        List<String> events = eventsFor(output, userId);
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"sign-in\"").contains("\"outcome\":\"success\""));
        assertThat(events).anySatisfy(line -> assertThat(line).contains("\"action\":\"sign-out\""));
        assertThat(output.getAll()).doesNotContain("logged@ci.annualva.test").doesNotContain("Logged Person");
    }

    @Test
    void refusedSignInIsLoggedWithAFixedReason(CapturedOutput output) {
        SignIn.through(api, issuer, TestIdentity.of("af-4002", "refused@ci.annualva.test", "Refused")
                .withAmr(List.of("pwd")));

        assertThat(output.getAll())
                .contains("\"action\":\"sign-in-refused\"")
                .contains("\"reason\":\"mfa-not-evidenced\"")
                .doesNotContain("refused@ci.annualva.test");
    }

    @Test
    void idleTimeoutIsLogged(CapturedOutput output) {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4003", "idle.log@ci.annualva.test", "Idle Log"));
        clock.advance(Duration.ofMinutes(31));
        api.get("/api/session");

        assertThat(output.getAll()).contains("\"action\":\"session-ended\"").contains("\"reason\":\"idle-timeout\"");
    }

    @Test
    void absoluteLimitIsLogged(CapturedOutput output) {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-4004", "abs.log@ci.annualva.test", "Abs Log"));
        for (int i = 0; i < 17; i++) {
            clock.advance(Duration.ofMinutes(29));
            api.get("/api/probe/whoami");
        }

        assertThat(output.getAll()).contains("\"action\":\"session-ended\"").contains("\"reason\":\"absolute-limit\"");
    }

    @Test
    void sessionEndedByANewerSignInIsLogged(CapturedOutput output) {
        var identity = TestIdentity.of("af-4005", "twice.log@ci.annualva.test", "Twice Log");
        SignIn.signedIn(api, issuer, identity);
        SignIn.signedIn(anotherBrowser(), issuer, identity);
        api.get("/api/probe/whoami");

        assertThat(output.getAll()).contains("\"action\":\"session-ended\"").contains("\"reason\":\"newer-sign-in\"");
    }

    private static List<String> eventsFor(CapturedOutput output, String userId) {
        return output.getAll().lines().filter(line -> line.contains(userId)).toList();
    }
}
