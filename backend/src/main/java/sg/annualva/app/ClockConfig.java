package sg.annualva.app;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock is a boundary adapter: every module reads time through this bean, never through
 * static now() calls, so Seam 1 tests can set "week N of Quarter Q".
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
