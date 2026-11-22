package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): the key signals are on {@code /actuator/prometheus}, for the monitoring system's scraper
 * alone (a bearer token, no session), and {@code /actuator/health} stays anonymous and says only that the process is up.
 */
class MetricsEndpointTest extends Seam1Test {

    private static final String PROMETHEUS = "/actuator/prometheus";

    @Test
    void theScrapeIsRefusedWithoutTheTokenAndWithAWrongOne() {
        var anonymous = api.get(PROMETHEUS);
        var wrong = api.get(PROMETHEUS, Map.of("Authorization", "Bearer " + Metrics.TOKEN + "x"));
        var prefix = api.get(PROMETHEUS, Map.of("Authorization", "Bearer " + Metrics.TOKEN.substring(0, 20)));
        var otherScheme = api.get(PROMETHEUS, Map.of("Authorization", "Basic " + Metrics.TOKEN));

        for (var refused : new ApiClient.Response[] {anonymous, wrong, prefix, otherScheme}) {
            assertThat(refused.status()).isEqualTo(401);
            assertThat(refused.header("content-type")).startsWith("application/problem+json");
            assertThat(refused.header("www-authenticate")).isEqualTo("Bearer");
            assertThat(refused.body()).as("no metric in a refusal").doesNotContain("annualva_");
        }
    }

    @Test
    void aSignedInPersonsSessionDoesNotOpenTheScrape() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-5201", "someone@ci.annualva.test", "Someone")
                .withAuthTime(clock.instant()));

        assertThat(api.get(PROMETHEUS).status()).as("the session is not the scraper's credential").isEqualTo(401);
    }

    @Test
    void theScrapePathWithATrailingSlashIsClosedToEveryone() {
        String slash = PROMETHEUS + "/";
        var anonymous = api.get(slash);
        var token = api.get(slash, Map.of("Authorization", "Bearer " + Metrics.TOKEN));
        SignIn.signedIn(api, issuer, TestIdentity.of("af-5202", "slash@ci.annualva.test", "Slash")
                .withAuthTime(clock.instant()));
        var session = api.get(slash);

        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(token.status()).as("the token is for the exact path only").isEqualTo(401);
        assertThat(session.status()).as("a session passes the application's chain, and nothing serves the path")
                .isEqualTo(404);
        for (var refused : new ApiClient.Response[] {anonymous, token, session}) {
            assertThat(refused.body()).as("no metric").doesNotContain("annualva_");
        }
    }

    @Test
    void theTokenOpensTheScrapeAndNothingElseUnderActuator() {
        var scrape = api.get(PROMETHEUS, Map.of("Authorization", "Bearer " + Metrics.TOKEN));
        assertThat(scrape.status()).isEqualTo(200);
        assertThat(scrape.header("content-type")).startsWith("text/plain");
        assertThat(scrape.header("set-cookie")).as("the scrape starts no session").isNull();
        assertThat(scrape.header("cache-control")).contains("no-store");
        assertThat(scrape.header("strict-transport-security")).contains("max-age=31536000");

        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/metrics", "/actuator/beans",
                "/actuator/loggers", "/actuator/prometheus/x"}) {
            assertThat(api.get(path, Map.of("Authorization", "Bearer " + Metrics.TOKEN)).status()).as(path)
                    .isEqualTo(401);
        }
    }

    /**
     * Each is registered at start, so it is on every scrape, whatever has happened. The shared context means a counter's
     * value here is whatever earlier tests left; an Overdue gauge is not-a-number until the first sweep.
     */
    @Test
    void everyKeySignalIsOnEveryScrape() {
        assertThat(metrics().names()).containsAll(Metrics.KEY_SIGNALS);
    }

    @Test
    void noSeriesCarriesAnythingButTheFixedNamesAndTags() {
        for (String series : metrics().names()) {
            if (series.startsWith("annualva_")) {
                assertThat(series).as("a key signal's name and tags are fixed words").matches("annualva_[a-z_]+(\\{kind=\"[a-z_]+\"})?");
            }
        }
    }

    @Test
    void healthIsAnonymousAndSaysOnlyThatTheProcessIsUp() {
        var response = api.get("/actuator/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(ApiClient.json(response).propertyNames()).as(response.body()).containsExactly("status");
        assertThat(ApiClient.json(response).get("status").asString()).isEqualTo("UP");
        assertThat(api.get("/actuator/health/db").status()).as("no component is reachable").isEqualTo(401);
    }
}
