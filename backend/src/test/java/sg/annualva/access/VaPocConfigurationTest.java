package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Story 75: the application refuses to start unless configuration names exactly two VA POCs. Start
 * up is not reachable over HTTP, so this runs the configuration binding the way the application does
 * at startup and asserts that the context fails to start or starts.
 */
class VaPocConfigurationTest {

    private final ApplicationContextRunner startup = new ApplicationContextRunner()
            .withUserConfiguration(Bindings.class);

    @EnableConfigurationProperties(VaPocProperties.class)
    static class Bindings {}

    @Test
    void twoDistinctEmailsStart() {
        startup.withPropertyValues("annualva.va-pocs=one@ci.annualva.test, two@ci.annualva.test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(VaPocProperties.class).vaPocs())
                    .containsExactly("one@ci.annualva.test", "two@ci.annualva.test");
        });
    }

    @Test
    void aReturnOfAConfiguredVaPocStartsWhateverItsSpelling() {
        startup.withPropertyValues("annualva.va-pocs=one@ci.annualva.test,two@ci.annualva.test",
                "annualva.va-poc-returns=Two@CI.annualva.test").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(VaPocProperties.class).vaPocReturns())
                            .containsExactly("Two@CI.annualva.test");
                });
    }

    @Test
    void aReturnOfSomeoneNotConfiguredIsRefused() {
        startup.withPropertyValues("annualva.va-pocs=one@ci.annualva.test,two@ci.annualva.test",
                "annualva.va-poc-returns=three@ci.annualva.test").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessage("annualva.va-poc-returns may name only the configured VA POCs");
                });
    }

    @Test
    void noneIsRefused() {
        startup.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void oneIsRefused() {
        startup.withPropertyValues("annualva.va-pocs=one@ci.annualva.test").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void threeAreRefused() {
        startup.withPropertyValues("annualva.va-pocs=a@ci.annualva.test,b@ci.annualva.test,c@ci.annualva.test")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theSamePersonTwiceIsRefusedHoweverTheyAreSpelled() {
        startup.withPropertyValues("annualva.va-pocs=One@ci.annualva.test,one@CI.annualva.test")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void somethingThatIsNotAnEmailIsRefused() {
        startup.withPropertyValues("annualva.va-pocs=one@ci.annualva.test,not-an-email")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theFailureNamesTheSettingAndNotItsValues() {
        startup.withPropertyValues("annualva.va-pocs=secret.person@ci.annualva.test").run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            var text = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                text.append(cause.getMessage()).append('\n');
            }
            assertThat(text).contains("annualva.va-pocs").doesNotContain("secret.person");
        });
    }
}
