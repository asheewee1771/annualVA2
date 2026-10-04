package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;

class HealthAndSessionTest extends Seam1Test {

    @Test
    void healthIsReachableAnonymously() {
        var response = api.get("/actuator/health");
        assertThat(response.status()).isEqualTo(200);
        assertThat(ApiClient.json(response).get("status").asString()).isEqualTo("UP");
    }

    @Test
    void sessionReportsNoSignInAndTheClassificationBanner() {
        var response = api.get("/api/session");
        assertThat(response.status()).isEqualTo(200);
        var body = ApiClient.json(response);
        assertThat(body.get("authenticated").asBoolean()).isFalse();
        assertThat(body.get("classification").asString()).isEqualTo("RESTRICTED / SENSITIVE-NORMAL");
    }

    @Test
    void csrfTokenCanBeBootstrappedAndUsed() {
        var csrf = ApiClient.json(api.get("/api/csrf"));
        assertThat(csrf.get("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
        assertThat(csrf.get("token").asString()).isNotBlank();

        var echoed = api.withCsrf().postJson("/api/test/echo", "{\"text\":\"hello\"}");
        assertThat(echoed.status()).isEqualTo(200);
        assertThat(ApiClient.json(echoed).get("text").asString()).isEqualTo("hello");
    }
}
