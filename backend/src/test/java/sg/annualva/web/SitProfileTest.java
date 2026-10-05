package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import sg.annualva.access.StepUpProperties;
import sg.annualva.harness.Seam1Test;

/**
 * The {@code sit} profile (ADR 0005) layered on the Seam 1 harness: it must start with the security
 * checks of the production shape, trust the SIT bundle by name and offer step-up in the mode ADR 0004
 * chooses. The SIT certificate is supplied by the SSO onboarding, so a missing file must not stop the
 * application from starting.
 */
@ActiveProfiles("sit")
class SitProfileTest extends Seam1Test {

    @Autowired
    private Environment environment;

    @Autowired
    private StepUpProperties stepUp;

    @Test
    void startsWithTheSecurityChecksOfTheProductionShape() {
        assertThat(environment.getProperty("annualva.sign-in.require-mfa-claim", Boolean.class)).isTrue();
        assertThat(environment.getProperty("annualva.sign-in.require-verified-email", Boolean.class)).isTrue();
        assertThat(stepUp.mode()).isEqualTo(StepUpProperties.Mode.PROMPT_LOGIN);
    }

    @Test
    void trustsMccSsoThroughTheSitBundle() {
        assertThat(environment.getProperty("annualva.mcc.ssl-bundle")).isEqualTo("mcc-trust");
        assertThat(environment.getProperty("spring.ssl.bundle.pem.mcc-trust.truststore.certificate"))
                .isEqualTo("classpath:sit/mcc-sso-ca.crt");
    }

    @Test
    void theLoginClientAndIdpHintComeFromTheEnvironmentWithMockDefaults() {
        assertThat(environment.getProperty("annualva.mcc.login-client-id")).isEqualTo("app-appfw");
        assertThat(environment.getProperty("annualva.mcc.idp-hint")).isEqualTo("singpass");
    }
}
