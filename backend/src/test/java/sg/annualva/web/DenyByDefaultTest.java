package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;

class DenyByDefaultTest extends Seam1Test {

    @Test
    void unknownApiPathNeedsASession() {
        var response = api.get("/api/anything");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(response.header("cache-control")).isEqualTo("no-store");
        var body = ApiClient.json(response);
        assertThat(body.get("type").asString()).isEqualTo("https://annualva/problems/unauthenticated");
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("traceId").asString()).isEqualTo(response.header("X-Correlation-Id"));
    }

    @Test
    void actuatorBeyondHealthAndInfoIsNotPublic() {
        assertThat(api.get("/actuator/env").status()).isEqualTo(401);
        assertThat(api.get("/actuator").status()).isEqualTo(401);
    }

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRefused() {
        var response = api.postJson("/api/test/echo", "{\"text\":\"hello\"}");
        assertThat(response.status()).isEqualTo(403);
        var body = ApiClient.json(response);
        assertThat(body.get("type").asString()).isEqualTo("https://annualva/problems/csrf-rejected");
        assertThat(body.get("traceId").asString()).isNotBlank();
    }
}
