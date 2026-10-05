package sg.annualva.adapters.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** The MCC SSO client contract: what the application sends the identity provider and publishes to it. */
class OidcClientTest extends Seam1Test {

    @Test
    void jwksPublishesOneEcP521SigningKeyAnonymously() {
        var response = api.get("/.well-known/jwks.json");
        assertThat(response.status()).isEqualTo(200);
        var keys = ApiClient.json(response).get("keys");
        assertThat(keys.size()).isEqualTo(1);
        var key = keys.get(0);
        assertThat(key.get("kty").asString()).isEqualTo("EC");
        assertThat(key.get("crv").asString()).isEqualTo("P-521");
        assertThat(key.get("alg").asString()).isEqualTo("ES512");
        assertThat(key.get("use").asString()).isEqualTo("sig");
        assertThat(key.get("kid").asString()).isEqualTo("ci-ec521");
        assertThat(key.has("d")).as("the private part is never published").isFalse();
    }

    @Test
    void authorizationRequestUsesPkceTheAfcasScopeAndTheIdpHint() {
        var flow = SignIn.through(api, issuer, TestIdentity.of("af-0001", "smo.one@ci.annualva.test", "SMO One"));
        var request = flow.authorizationRequest();
        assertThat(request.get("response_type")).isEqualTo("code");
        assertThat(request.get("client_id")).isEqualTo("app-appfw");
        assertThat(request.get("scope")).isEqualTo("openid afcas");
        assertThat(request.get("code_challenge_method")).isEqualTo("S256");
        assertThat(request.get("code_challenge")).isNotBlank();
        assertThat(request.get("kc_idp_hint")).isEqualTo("singpass");
        assertThat(request.get("nonce")).isNotBlank();
        assertThat(request.get("state")).isNotBlank();
        assertThat(request.get("redirect_uri")).endsWith("/login/oauth2/code/mcc-sso-login");
    }

    @Test
    void tokenRequestAuthenticatesWithAnEs512ClientAssertionSignedByThePublishedKey() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-0002", "smo.two@ci.annualva.test", "SMO Two"));
        var token = issuer.lastTokenRequest();
        assertThat(token.grantType()).isEqualTo("authorization_code");
        assertThat(token.pkceValid()).isTrue();
        assertThat(token.clientAssertionType()).isEqualTo("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        assertThat(token.clientAssertionAlgorithm()).isEqualTo("ES512");
        assertThat(token.clientAssertionKid()).isEqualTo("ci-ec521");
        assertThat(token.clientAssertionValid()).isTrue();
    }
}
