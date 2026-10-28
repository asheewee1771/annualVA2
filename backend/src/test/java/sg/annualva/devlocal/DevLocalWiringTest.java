package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import sg.annualva.boundary.FileStore;
import sg.annualva.boundary.IdentityProvider;
import sg.annualva.boundary.IdentityStatus;
import sg.annualva.boundary.MailRelay;
import sg.annualva.boundary.MalwareScanner;
import sg.annualva.harness.ApiClient;

/**
 * Ticket 63, ADR 0058: under dev-local the four boundary ports are the local fakes and nothing else, there is no
 * identity-provider client at all (no registration is built, so nothing could be discovered at startup), and the files a
 * developer uploads are kept in the local folder, zone by zone.
 */
class DevLocalWiringTest extends DevLocalSeam1 {

    @Autowired
    private ApplicationContext context;

    @Test
    void eachBoundaryPortIsTheLocalFakeAndNothingElse() {
        for (Class<?> port : List.of(FileStore.class, MalwareScanner.class, MailRelay.class, IdentityStatus.class)) {
            assertThat(context.getBeansOfType(port).values()).as(port.getSimpleName()).singleElement()
                    .satisfies(bean -> assertThat(bean.getClass().getSimpleName()).startsWith("Local"));
        }
    }

    @Test
    void noIdentityProviderClientExists() {
        assertThat(context.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(OAuth2AuthorizationRequestResolver.class)).isEmpty();
        assertThat(context.getBeansOfType(IdentityProvider.class)).isEmpty();
        assertThat(browser().get("/.well-known/jwks.json").status()).isEqualTo(404);
        assertThat(browser().get("/oauth2/authorization/mcc-sso-login").status()).isEqualTo(404);
    }

    @Test
    void theSingleSignOnPathsHaveNoStepUpRoundTripEither() {
        ApiClient poc = signedInAs(POC_1);

        var started = poc.get("/auth/step-up?intent=probe-sensitive");

        assertThat(started.status()).isEqualTo(302);
        assertThat(started.header("location")).contains("/dev-step-up?intent=probe-sensitive");
    }

    @Test
    void aRegisterUploadIsScannedLocallyAndLeavesNothingInQuarantine() throws Exception {
        ApiClient poc = signedInAs(POC_1);
        var upload = poc.postFile("/api/system-register/uploads", "file", "register.xlsx", XLSX,
                sg.annualva.harness.RegisterXlsx.register()
                        .row("DEV-1", "Payroll", "smo.a@dev.annualva.test", "cm.a@dev.annualva.test", "Q3").build());

        assertThat(upload.status()).as(upload.body()).isEqualTo(201);
        try (Stream<java.nio.file.Path> quarantine = Files.exists(fileStoreFolder().resolve("quarantine"))
                ? Files.list(fileStoreFolder().resolve("quarantine")) : Stream.empty()) {
            assertThat(quarantine.toList()).as("the quarantined copy never outlives the scan").isEmpty();
        }
    }
}
