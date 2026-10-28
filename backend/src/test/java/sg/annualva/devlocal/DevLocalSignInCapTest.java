package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.annualva.harness.ApiClient;

/**
 * Ticket 63: the persona sign-in is behind the same anonymous-session cap as the single sign-on's (as-4). A sign-in needs
 * a CSRF token, and fetching one without a session starts a session row; a client address may start only so many a
 * minute, and a person who already has a session is not counted, so signing in is never refused for the cap.
 */
class DevLocalSignInCapTest extends DevLocalSeam1 {

    @DynamicPropertySource
    static void lowCap(DynamicPropertyRegistry registry) {
        registry.add("annualva.sign-in.anonymous-sessions-per-minute", () -> 2);
    }

    @Test
    void aClientAddressCannotStartMoreSessionsThanTheCapAndASessionAlreadyStartedStillSignsIn() {
        // The base class has already started one (its Register reset); the cap is two a minute.
        ApiClient first = browser();
        assertThat(first.get("/api/csrf").status()).isEqualTo(200);

        ApiClient refused = browser();
        var answer = refused.get("/api/csrf");

        assertThat(answer.status()).isEqualTo(429);
        assertThat(answer.header("retry-after")).isEqualTo("60");
        assertThat(signIn(first.withCsrf(), POC_1).status()).as("the person who has a session is not counted").isEqualTo(204);
    }
}
