package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** Story 72: who may sign in, how a sign-in is linked to a user, and what the session shows. */
@ExtendWith(OutputCaptureExtension.class)
class SignInTest extends Seam1Test {

    @Test
    void signedInPersonSeesTheirIdentityAndSessionLimits() {
        clock.set(java.time.Instant.parse("2027-08-02T01:00:00Z"));
        SignIn.signedIn(api, issuer, TestIdentity.of("af-1001", "smo.alpha@ci.annualva.test", "SMO Alpha"));

        var session = ApiClient.json(api.get("/api/session"));
        assertThat(session.get("authenticated").asBoolean()).isTrue();
        assertThat(session.get("uuid").asString()).matches("[0-9a-f-]{36}");
        assertThat(session.get("email").asString()).isEqualTo("smo.alpha@ci.annualva.test");
        assertThat(session.get("name").asString()).isEqualTo("SMO Alpha");
        assertThat(session.get("roles").isArray()).isTrue();
        assertThat(session.get("roles").size()).isZero();
        assertThat(session.get("classification").asString()).isEqualTo("RESTRICTED / SENSITIVE-NORMAL");
        assertThat(session.get("idleTimeoutSeconds").asInt()).isEqualTo(1800);
        assertThat(session.get("absoluteExpiresAt").asString()).isEqualTo("2027-08-02T09:00:00Z");

        var protectedCall = api.get("/api/probe/whoami");
        assertThat(protectedCall.status()).isEqualTo(200);
        assertThat(ApiClient.json(protectedCall).get("name").asString()).isEqualTo(session.get("uuid").asString());
    }

    @Test
    void firstSignInLinksToThePreRegisteredEmailAndLaterOnesRecogniseTheAfcasUuid() {
        var preRegistered = ApiClient.json(api.withCsrf().postJson("/api/test/users",
                "{\"email\":\"SMO.Beta@ci.annualva.test\"}")).get("uuid").asString();

        SignIn.signedIn(api, issuer, TestIdentity.of("af-1002", "smo.beta@ci.annualva.test", "SMO Beta"));
        var first = ApiClient.json(api.get("/api/session"));
        assertThat(first.get("uuid").asString()).as("linked by email, case-insensitively").isEqualTo(preRegistered);

        api.withCsrf().post("/api/logout");

        var renamed = TestIdentity.of("af-1002", "beta.renamed@ci.annualva.test", "Beta Renamed").flatAfcasClaim();
        SignIn.signedIn(api, issuer, renamed);
        var second = ApiClient.json(api.get("/api/session"));
        assertThat(second.get("uuid").asString()).as("recognised by afcas.uuid despite the new email").isEqualTo(preRegistered);
        assertThat(second.get("email").asString()).isEqualTo("beta.renamed@ci.annualva.test");
        assertThat(second.get("name").asString()).isEqualTo("Beta Renamed");
    }

    @Test
    void unknownPersonGetsANewUserRecord() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-1003", "cm.gamma@ci.annualva.test", "CM Gamma"));
        var session = ApiClient.json(api.get("/api/session"));
        assertThat(session.get("authenticated").asBoolean()).isTrue();
        assertThat(session.get("email").asString()).isEqualTo("cm.gamma@ci.annualva.test");

        var other = anotherBrowser();
        SignIn.signedIn(other, issuer, TestIdentity.of("af-1004", "cm.delta@ci.annualva.test", "CM Delta"));
        assertThat(ApiClient.json(other.get("/api/session")).get("uuid").asString())
                .isNotEqualTo(session.get("uuid").asString());
    }

    @Test
    void personaWithoutAnEmailClaimIsResolvedThroughTheConfiguredIdentities() {
        SignIn.signedIn(api, issuer,
                TestIdentity.of("11111111-0000-4000-8000-000000000099", null, "Persona 99").withEmail(null));
        var session = ApiClient.json(api.get("/api/session"));
        assertThat(session.get("email").asString()).isEqualTo("persona.no-email@ci.annualva.test");
    }

    @Test
    void tokenWithoutAfcasUuidIsRefused() {
        assertRefused(TestIdentity.of("af-1005", "no.uuid@ci.annualva.test", "No Uuid").withoutSsoId(), "missing-identity");
    }

    @Test
    void tokenWithoutAuthTimeIsRefused() {
        assertRefused(TestIdentity.of("af-1006", "no.authtime@ci.annualva.test", "No AuthTime").withoutAuthTime(),
                "missing-auth-time");
    }

    @Test
    void tokenWithoutMfaEvidenceIsRefused() {
        assertRefused(TestIdentity.of("af-1007", "pwd.only@ci.annualva.test", "Password Only").withAmr(List.of("pwd")),
                "mfa-not-evidenced");
        assertRefused(TestIdentity.of("af-1007", "pwd.only@ci.annualva.test", "Password Only").withAmr(null),
                "mfa-not-evidenced");
    }

    @Test
    void mfaEvidenceMayComeFromTheConfiguredAcrLevel() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-1008", "acr.user@ci.annualva.test", "Acr User")
                .withAmr(null).withAcr("urn:annualva:ci:mfa"));
        assertThat(ApiClient.json(api.get("/api/session")).get("authenticated").asBoolean()).isTrue();
    }

    @Test
    void tokenWithoutAnyEmailIsRefused() {
        assertRefused(TestIdentity.of("af-1009", null, "Nobody").withEmail(null), "missing-email");
    }

    @Test
    void tokenTheClientCannotValidateIsRefusedWithoutDetail() {
        var flow = SignIn.through(api, issuer,
                TestIdentity.of("af-1010", "bad.nonce@ci.annualva.test", "Bad Nonce").withTamperedNonce());
        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/?signin=refused&reason=provider-error");
        assertThat(flow.callback().body()).doesNotContain("nonce").doesNotContain("Exception");
    }

    @Test
    void emailTheProviderDoesNotAssertAsVerifiedIsRefusedAndLinksNobody() {
        var preRegistered = ApiClient.json(api.withCsrf().postJson("/api/test/users",
                "{\"email\":\"victim@ci.annualva.test\"}")).get("uuid").asString();

        assertRefused(api, TestIdentity.of("af-1015", "victim@ci.annualva.test", "Impostor").withEmailVerified(false),
                "email-not-verified");
        assertRefused(anotherBrowser(),
                TestIdentity.of("af-1015", "victim@ci.annualva.test", "Impostor").withEmailVerified(null),
                "email-not-verified");

        var owner = anotherBrowser();
        SignIn.signedIn(owner, issuer, TestIdentity.of("af-1016", "victim@ci.annualva.test", "Owner"));
        assertThat(ApiClient.json(owner.get("/api/session")).get("uuid").asString())
                .as("the pre-registered identity is still unlinked and goes to its verified owner")
                .isEqualTo(preRegistered);
    }

    @Test
    void differentPersonClaimingALinkedEmailIsRefusedAsAnIdentityConflict() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-1011", "taken@ci.annualva.test", "First Holder"));
        api.withCsrf().post("/api/logout");

        var other = anotherBrowser();
        assertRefused(other, TestIdentity.of("af-1012", "Taken@ci.annualva.test", "Second Claimant"),
                "identity-conflict");
    }

    @Test
    void identityConflictIsLoggedWithoutEmailOrName(CapturedOutput output) {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-1013", "held@ci.annualva.test", "Holder"));
        api.withCsrf().post("/api/logout");

        assertRefused(anotherBrowser(), TestIdentity.of("af-1014", "held@ci.annualva.test", "Claimant Name"),
                "identity-conflict");

        assertThat(output.getAll())
                .contains("\"action\":\"sign-in-refused\"")
                .contains("\"reason\":\"identity-conflict\"")
                .doesNotContain("Claimant Name")
                .doesNotContain("held@ci.annualva.test");
    }

    private void assertRefused(TestIdentity identity, String reason) {
        assertRefused(api, identity, reason);
    }

    private void assertRefused(ApiClient browser, TestIdentity identity, String reason) {
        var flow = SignIn.through(browser, issuer, identity);
        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/?signin=refused&reason=" + reason);
        assertThat(ApiClient.json(browser.get("/api/session")).get("authenticated").asBoolean()).isFalse();
        assertThat(browser.get("/api/probe/whoami").status()).isEqualTo(401);
    }
}
