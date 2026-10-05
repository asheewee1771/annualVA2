package sg.annualva.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;

/**
 * Drives the browser's side of the authorization-code flow: start at the application, follow the
 * redirect to the fake issuer, follow its redirect back to the application's callback. Tests
 * assert on the recorded authorization request and on the callback response.
 */
public final class SignIn {

    /** @param authorizationRequest the query the application sent the issuer; @param callback the application's answer to the code */
    public record Flow(Map<String, String> authorizationRequest, ApiClient.Response callback) {}

    private SignIn() {}

    public static Flow through(ApiClient api, FakeOidcIssuer issuer, TestIdentity identity) {
        issuer.nextSignIn(identity);
        var start = api.get("/oauth2/authorization/mcc-sso-login");
        assertThat(start.status()).as("the application starts the authorization-code flow").isEqualTo(302);
        String authorizeUrl = start.header("location");
        var authorizationRequest = FakeOidcIssuer.decode(URI.create(authorizeUrl).getRawQuery());
        var authorized = api.getUrl(authorizeUrl);
        assertThat(authorized.status()).as("the issuer redirects back with a code").isEqualTo(302);
        var callback = api.getUrl(authorized.header("location"));
        return new Flow(authorizationRequest, callback);
    }

    /**
     * Drives a step-up from the signed-in browser: starts at the given application path, follows the
     * redirect to the fake issuer (which asserts {@code identity}) and then back to the callback.
     *
     * @param start the application path that starts the step-up, such as {@code /auth/step-up?intent=probe-sensitive}
     */
    public static Flow stepUp(ApiClient api, FakeOidcIssuer issuer, String start, TestIdentity identity) {
        issuer.nextSignIn(identity);
        var started = api.get(start);
        assertThat(started.status()).as("the application sends the browser to the issuer").isEqualTo(302);
        String authorizeUrl = started.header("location");
        var authorizationRequest = FakeOidcIssuer.decode(URI.create(authorizeUrl).getRawQuery());
        var authorized = api.getUrl(authorizeUrl);
        assertThat(authorized.status()).as("the issuer redirects back with a code").isEqualTo(302);
        return new Flow(authorizationRequest, api.getUrl(authorized.header("location")));
    }

    /** Signs in and asserts the application accepted it (redirect to the front end). */
    public static ApiClient signedIn(ApiClient api, FakeOidcIssuer issuer, TestIdentity identity) {
        var flow = through(api, issuer, identity);
        assertThat(flow.callback().status()).isEqualTo(302);
        assertThat(flow.callback().header("location")).endsWith("/");
        return api;
    }
}
