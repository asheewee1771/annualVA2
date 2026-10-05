package sg.annualva.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * The first live Quarter is a one-off setting the calendar cannot run without. Start-up is not reachable over HTTP,
 * so this runs the configuration binding the way the application does at startup.
 */
class CalendarConfigurationTest {

    private final ApplicationContextRunner startup = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Bindings.class);

    @EnableConfigurationProperties(CalendarProperties.class)
    static class Bindings {}

    @Test
    void aCycleAndAQuarterStart() {
        startup.withPropertyValues("annualva.calendar.first-live-cycle=2027", "annualva.calendar.first-live-quarter=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CalendarProperties.class))
                            .isEqualTo(new CalendarProperties(2027, 3));
                });
    }

    @Test
    void noSettingIsRefused() {
        startup.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aQuarterOutsideOneToFourIsRefused() {
        for (String quarter : new String[] {"0", "5", "-1"}) {
            startup.withPropertyValues("annualva.calendar.first-live-cycle=2027", "annualva.calendar.first-live-quarter=" + quarter)
                    .run(context -> assertThat(context).as(quarter).hasFailed());
        }
    }

    @Test
    void aCycleOutsideTheSupportedYearsIsRefused() {
        for (String cycle : new String[] {"1999", "2100", "0"}) {
            startup.withPropertyValues("annualva.calendar.first-live-cycle=" + cycle, "annualva.calendar.first-live-quarter=3")
                    .run(context -> assertThat(context).as(cycle).hasFailed());
        }
    }
}
