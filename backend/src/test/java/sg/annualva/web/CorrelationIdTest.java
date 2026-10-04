package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.Seam1Test;

class CorrelationIdTest extends Seam1Test {

    @Test
    void wellFormedCallerIdIsEchoed() {
        var response = api.get("/actuator/health", Map.of("X-Correlation-Id", "req-2027-08-02.0001"));
        assertThat(response.header("X-Correlation-Id")).isEqualTo("req-2027-08-02.0001");
    }

    @Test
    void malformedCallerIdIsReplaced() {
        var response = api.get("/actuator/health", Map.of("X-Correlation-Id", "bad id with spaces"));
        assertThat(response.header("X-Correlation-Id"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void everyResponseCarriesAnId() {
        var first = api.get("/api/session").header("X-Correlation-Id");
        var second = api.get("/api/session").header("X-Correlation-Id");
        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }
}
