package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;

class ErrorResponseTest extends Seam1Test {

    @Test
    void unexpectedFailureIsAGenericProblemWithTraceIdAndNoInternalDetail() {
        var response = api.get("/api/test/boom");
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(response.body())
                .doesNotContain("secret internal detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at sg.annualva");
        var body = ApiClient.json(response);
        assertThat(body.get("type").asString()).isEqualTo("https://annualva/problems/internal-error");
        assertThat(body.get("traceId").asString()).isEqualTo(response.header("X-Correlation-Id"));
    }

    @Test
    void invalidRequestBodyIsABadRequestProblemWithTraceId() {
        var response = api.withCsrf().postJson("/api/test/echo", "{\"text\":\"\"}");
        assertThat(response.status()).isEqualTo(400);
        var body = ApiClient.json(response);
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("traceId").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("MethodArgumentNotValidException");
    }

    @Test
    void missingStaticResourceIsANotFoundProblem() {
        var response = api.get("/assets/does-not-exist.js");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(ApiClient.json(response).get("traceId").asString()).isNotBlank();
    }
}
