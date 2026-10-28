package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import sg.annualva.boundary.FileStore;
import sg.annualva.boundary.IdentityStatus;
import sg.annualva.boundary.MailRelay;
import sg.annualva.boundary.MalwareScanner;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * Ticket 63, ADR 0058: outside the dev-local profile there is no persona endpoint, no sign-in as a persona and none of
 * the local fakes. This runs under the ordinary Seam 1 profile, where the harness fakes are the only ones.
 */
class DevLocalAbsentTest extends Seam1Test {

    private static final String PERSONA = "11111111-0000-4000-8000-000000000005";

    @Autowired
    private ApplicationContext context;

    @Test
    void thePersonaListIsNotFound() {
        assertThat(api.get("/api/dev/personas").status()).isEqualTo(404);
    }

    @Test
    void signingInAsAPersonaIsNotFoundAndCreatesNoSession() {
        api.withCsrf();

        var answer = api.postJson("/api/dev/sign-in", "{\"persona\":\"" + PERSONA + "\"}");

        assertThat(answer.status()).isEqualTo(404);
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isFalse();
        assertThat(api.get("/api/probe/whoami").status()).isEqualTo(401);
    }

    @Test
    void signingInAsAPersonaDoesNotSignInAPersonWhoIsAlreadySignedInEither() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-dev-absent", "absent@ci.annualva.test", "Absent"));
        api.withCsrf();

        assertThat(api.postJson("/api/dev/sign-in", "{\"persona\":\"" + PERSONA + "\"}").status()).isEqualTo(404);
        assertThat(ApiClient.json(api.get("/api/session")).get("email").asString())
                .isEqualTo("absent@ci.annualva.test");
    }

    @Test
    void theLocalConfirmationOfAStepUpIsNotFoundEither() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-dev-absent-2", "absent2@ci.annualva.test", "Absent"));
        api.withCsrf();

        assertThat(api.postJson("/api/dev/step-up", "{\"intent\":\"probe-sensitive\"}").status()).isEqualTo(404);
    }

    @Test
    void noLocalFakeIsInTheContext() {
        for (Class<?> port : new Class<?>[] {FileStore.class, MalwareScanner.class, MailRelay.class, IdentityStatus.class}) {
            assertThat(context.getBeansOfType(port).values()).as(port.getSimpleName())
                    .allSatisfy(bean -> assertThat(bean.getClass().getSimpleName()).doesNotStartWith("Local"));
        }
        assertThat(context.getBeanNamesForType(FileStore.class)).doesNotContain("localFileStore");
    }

}
