package sg.annualva.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ADR 0044 item 3: the application refuses to start without the app's https address or a relay template id for every
 * logical template, so a mail never goes out with a wrong link or an unknown template. Start up is not reachable over
 * HTTP, so this runs the configuration binding the way the application does at startup.
 */
class MailConfigurationTest {

    private static final String URL = "annualva.mail.app-url=https://annualva.ci.test";
    private static final String REWORK = "annualva.mail.templates.returned-for-rework=t-rework";
    private static final String OVERDUE = "annualva.mail.templates.overdue=t-overdue";
    private static final String CHANGE = "annualva.mail.templates.register-change=t-change";

    private final ApplicationContextRunner startup = new ApplicationContextRunner()
            .withUserConfiguration(Bindings.class);

    @EnableConfigurationProperties(MailProperties.class)
    static class Bindings {}

    @Test
    void anAddressAndEveryTemplateStartWithTheProductionRetries() {
        startup.withPropertyValues(URL, REWORK, OVERDUE, CHANGE).run(context -> {
            assertThat(context).hasNotFailed();
            MailProperties mail = context.getBean(MailProperties.class);
            assertThat(mail.templateId(MailTemplate.REGISTER_CHANGE)).isEqualTo("t-change");
            assertThat(mail.maxAttempts()).isEqualTo(5);
        });
    }

    @Test
    void aMissingTemplateIdIsRefused() {
        startup.withPropertyValues(URL, REWORK, OVERDUE).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aBlankTemplateIdIsRefused() {
        startup.withPropertyValues(URL, REWORK, OVERDUE, "annualva.mail.templates.register-change= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void noTemplatesAtAllIsRefused() {
        startup.withPropertyValues(URL).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anAddressThatIsNotHttpsOrHasAPathIsRefused() {
        for (String url : new String[] {"http://annualva.ci.test", "https://annualva.ci.test/", "https://a.test/app"}) {
            startup.withPropertyValues("annualva.mail.app-url=" + url, REWORK, OVERDUE, CHANGE)
                    .run(context -> assertThat(context).as(url).hasFailed());
        }
        startup.withPropertyValues(REWORK, OVERDUE, CHANGE).run(context -> assertThat(context).hasFailed());
    }
}
