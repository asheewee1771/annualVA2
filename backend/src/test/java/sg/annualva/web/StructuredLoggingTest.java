package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import sg.annualva.harness.Seam1Test;

class StructuredLoggingTest extends Seam1Test {

    @Autowired
    private Environment environment;

    @Test
    void applicationLogsAreEcsJson() {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("logging.structured.ecs.service.name")).isEqualTo("annualva");
    }

    @Test
    void virtualThreadsAndBusinessZoneAreConfigured() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("annualva.time-zone")).isEqualTo("Asia/Singapore");
    }
}
