package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** as-11 and ADR 0008: rotation at sign-in, idle and absolute limits, one session per person, sign-out. */
class SessionLifecycleTest extends Seam1Test {

    @Test
    void sessionIdChangesAtSignIn() {
        api.get("/api/csrf");
        String before = api.cookie("SESSION");
        assertThat(before).isNotBlank();

        SignIn.signedIn(api, issuer, TestIdentity.of("af-2001", "rotate@ci.annualva.test", "Rotate"));
        assertThat(api.cookie("SESSION")).isNotBlank().isNotEqualTo(before);
    }

    @Test
    void sessionEndsAfterThirtyMinutesIdle() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-2002", "idle@ci.annualva.test", "Idle"));

        clock.advance(Duration.ofMinutes(20));
        assertThat(api.get("/api/probe/whoami").status()).as("activity keeps the session").isEqualTo(200);
        clock.advance(Duration.ofMinutes(20));
        assertThat(api.get("/api/probe/whoami").status()).as("idle is measured from the last request").isEqualTo(200);

        clock.advance(Duration.ofMinutes(31));
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
        assertThat(api.get("/api/probe/whoami").status()).isEqualTo(401);
    }

    @Test
    void sessionEndsEightHoursAfterSignInHoweverActive() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-2003", "absolute@ci.annualva.test", "Absolute"));

        for (int i = 0; i < 16; i++) {
            clock.advance(Duration.ofMinutes(29));
            assertThat(api.get("/api/probe/whoami").status()).isEqualTo(200);
        }
        clock.advance(Duration.ofMinutes(29));
        assertThat(api.get("/api/probe/whoami").status()).as("8 h 05 min after sign-in").isEqualTo(401);
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
    }

    @Test
    void secondSignInByTheSamePersonEndsTheFirstSession() {
        var identity = TestIdentity.of("af-2004", "twice@ci.annualva.test", "Twice");
        SignIn.signedIn(api, issuer, identity);
        assertThat(api.get("/api/probe/whoami").status()).isEqualTo(200);

        var second = anotherBrowser();
        SignIn.signedIn(second, issuer, identity);
        assertThat(second.get("/api/probe/whoami").status()).isEqualTo(200);

        var evicted = api.get("/api/probe/whoami");
        assertThat(evicted.status()).isEqualTo(401);
        assertThat(ApiClient.json(evicted).get("type").asString()).isEqualTo("https://annualva/problems/unauthenticated");
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
    }

    @Test
    void signOutNeedsTheCsrfTokenEndsTheSessionAndClearsSiteData() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-2005", "bye@ci.annualva.test", "Bye"));

        var withoutToken = api.post("/api/logout");
        assertThat(withoutToken.status()).isEqualTo(403);
        assertThat(ApiClient.json(withoutToken).get("type").asString()).isEqualTo("https://annualva/problems/csrf-rejected");
        assertThat(api.get("/api/probe/whoami").status()).as("a refused sign-out changes nothing").isEqualTo(200);

        var signedOut = api.withCsrf().post("/api/logout");
        assertThat(signedOut.status()).isEqualTo(204);
        assertThat(signedOut.header("clear-site-data")).isEqualTo("\"cookies\",\"storage\"");
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
        assertThat(api.get("/api/probe/whoami").status()).isEqualTo(401);
    }

    @Test
    void thereIsNoFormLogin() {
        assertThat(api.withCsrf().postJson("/login", "{\"username\":\"x\",\"password\":\"y\"}").status()).isEqualTo(404);
        assertThat(api.get("/login").status()).isEqualTo(404);
    }
}
