package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.Seam1Test;

class SecurityHeadersTest extends Seam1Test {

    @Test
    void everyResponseCarriesTheIm8Headers() {
        var response = api.get("/api/session");
        assertThat(response.header("content-security-policy")).isEqualTo(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
                + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        assertThat(response.header("strict-transport-security")).isEqualTo("max-age=31536000 ; includeSubDomains");
        assertThat(response.header("x-frame-options")).isEqualTo("DENY");
        assertThat(response.header("x-content-type-options")).isEqualTo("nosniff");
        assertThat(response.header("referrer-policy")).isEqualTo("no-referrer");
        assertThat(response.header("cross-origin-opener-policy")).isEqualTo("same-origin");
        assertThat(response.header("permissions-policy")).contains("camera=()").contains("geolocation=()");
        assertThat(response.header("x-permitted-cross-domain-policies")).isEqualTo("none");
        assertThat(response.header("cache-control")).contains("no-store");
    }

    @Test
    void refusedResponsesCarryTheSameHeaders() {
        var response = api.get("/api/anything");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("content-security-policy")).contains("frame-ancestors 'none'");
        assertThat(response.header("strict-transport-security")).startsWith("max-age=31536000");
    }
}
