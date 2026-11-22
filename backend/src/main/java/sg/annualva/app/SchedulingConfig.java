package sg.annualva.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The scheduler, on only where {@code annualva.scheduling.enabled} is true (ADR 0012). The switch is here, not on each job:
 * the {@code *Job} beans exist in every profile, and without this the {@code @Scheduled} methods are plain methods nothing
 * calls, so Seam 1 tests (scheduling off) run each job's own bean by hand (ticket 67, ADR 0060 item 8).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class SchedulingConfig {
}
