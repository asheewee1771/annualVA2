package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** as-4: a client that keeps arriving without a session cannot make the session store grow without bound. */
@TestPropertySource(properties = "annualva.sign-in.anonymous-sessions-per-minute=5")
class AnonymousSessionLimitTest extends Seam1Test {

    private static final String START = "/oauth2/authorization/mcc-sso-login";

    @Autowired
    private JdbcClient jdbc;

    @Test
    void sessionlessRequestsThatStartASessionAreCappedPerMinuteAndCreateNothingWhenRefused() {
        clock.advance(Duration.ofMinutes(2));
        int before = storedSessions();
        for (int i = 0; i < 5; i++) {
            assertThat(anotherBrowser().get(START).status()).as("attempt " + (i + 1)).isEqualTo(302);
        }
        assertThat(storedSessions()).isEqualTo(before + 5);

        var refused = anotherBrowser().get(START);
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("retry-after")).isEqualTo("60");
        assertThat(ApiClient.json(refused).get("type").asString()).isEqualTo("https://annualva/problems/too-many-requests");
        assertThat(anotherBrowser().get("/api/csrf").status()).as("the CSRF bootstrap shares the cap").isEqualTo(429);
        assertThat(storedSessions()).as("a refused request writes no row").isEqualTo(before + 5);

        clock.advance(Duration.ofMinutes(2));
        assertThat(anotherBrowser().get(START).status()).as("the next minute starts a new window").isEqualTo(302);
    }

    @Test
    void requestsThatCarryASessionAndOtherPathsAreNotCounted() {
        clock.advance(Duration.ofMinutes(2));
        var browser = anotherBrowser();
        for (int i = 0; i < 12; i++) {
            assertThat(browser.get(START).status()).as("same session, attempt " + (i + 1)).isEqualTo(302);
        }
        for (int i = 0; i < 12; i++) {
            assertThat(anotherBrowser().get("/api/session").status()).isEqualTo(200);
        }
    }

    @Test
    void aSignedInPersonIsNotThrottledByTheirOwnSession() {
        clock.advance(Duration.ofMinutes(2));
        SignIn.signedIn(api, issuer, TestIdentity.of("af-5001", "limit.ok@ci.annualva.test", "Limit Ok"));
        for (int i = 0; i < 12; i++) {
            assertThat(api.get("/api/probe/whoami").status()).isEqualTo(200);
        }
    }

    private int storedSessions() {
        return jdbc.sql("SELECT count(*) FROM spring_session").query(Integer.class).single();
    }
}
