package sg.annualva.harness;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The identity the fake issuer will assert at the next sign-in: what MCC SSO would put in the id
 * token. Knobs cover every refusal the sign-in slice must prove (no afcas.uuid, no auth_time, no
 * MFA evidence, no email) and the claim shapes the identity provider must accept.
 *
 * @param ssoId the {@code afcas.uuid} value, or null to omit the claim
 * @param email the verified email claim, or null to omit it
 * @param name the display name claim
 * @param authTime the {@code auth_time} claim, or null to omit it
 * @param amr the {@code amr} claim, or null to omit it
 * @param acr the {@code acr} claim, or null to omit it
 * @param nestedAfcasClaim true for {@code "afcas": {"uuid": ...}}, false for the flat {@code "afcas.uuid"} claim
 * @param emailVerified the {@code email_verified} claim, or null to omit it
 * @param tamperedNonce true to echo a wrong nonce so the id token fails validation at the client
 * @param extraClaims further id-token claims the provider might send (for example a claimed role)
 */
public record TestIdentity(
        @Nullable String ssoId,
        @Nullable String email,
        String name,
        @Nullable Instant authTime,
        @Nullable List<String> amr,
        @Nullable String acr,
        boolean nestedAfcasClaim,
        @Nullable Boolean emailVerified,
        boolean tamperedNonce,
        Map<String, Object> extraClaims) {

    /** A well-formed MFA sign-in: nested afcas.uuid, auth_time now, amr [pwd, mfa]. */
    public static TestIdentity of(String ssoId, String email, String name) {
        return new TestIdentity(ssoId, email, name, Instant.now(), List.of("pwd", "mfa"), null, true, true, false,
                Map.of());
    }

    public TestIdentity withoutSsoId() {
        return new TestIdentity(null, email, name, authTime, amr, acr, nestedAfcasClaim, emailVerified, tamperedNonce,
                extraClaims);
    }

    public TestIdentity withEmail(@Nullable String newEmail) {
        return new TestIdentity(ssoId, newEmail, name, authTime, amr, acr, nestedAfcasClaim, emailVerified,
                tamperedNonce, extraClaims);
    }

    public TestIdentity withoutAuthTime() {
        return new TestIdentity(ssoId, email, name, null, amr, acr, nestedAfcasClaim, emailVerified, tamperedNonce,
                extraClaims);
    }

    /** Asserts that the provider authenticated the person at this instant, stale or fresh. */
    public TestIdentity withAuthTime(@Nullable Instant newAuthTime) {
        return new TestIdentity(ssoId, email, name, newAuthTime, amr, acr, nestedAfcasClaim, emailVerified,
                tamperedNonce, extraClaims);
    }

    public TestIdentity withAmr(@Nullable List<String> newAmr) {
        return new TestIdentity(ssoId, email, name, authTime, newAmr, acr, nestedAfcasClaim, emailVerified,
                tamperedNonce, extraClaims);
    }

    public TestIdentity withAcr(@Nullable String newAcr) {
        return new TestIdentity(ssoId, email, name, authTime, amr, newAcr, nestedAfcasClaim, emailVerified,
                tamperedNonce, extraClaims);
    }

    public TestIdentity flatAfcasClaim() {
        return new TestIdentity(ssoId, email, name, authTime, amr, acr, false, emailVerified, tamperedNonce,
                extraClaims);
    }

    public TestIdentity withTamperedNonce() {
        return new TestIdentity(ssoId, email, name, authTime, amr, acr, nestedAfcasClaim, emailVerified, true,
                extraClaims);
    }

    public TestIdentity withEmailVerified(@Nullable Boolean verified) {
        return new TestIdentity(ssoId, email, name, authTime, amr, acr, nestedAfcasClaim, verified, tamperedNonce,
                extraClaims);
    }

    /** Adds a claim to the id token, as an identity provider asserting a role or group would. */
    public TestIdentity withClaim(String claim, Object value) {
        var merged = new LinkedHashMap<>(extraClaims);
        merged.put(claim, value);
        return new TestIdentity(ssoId, email, name, authTime, amr, acr, nestedAfcasClaim, emailVerified,
                tamperedNonce, merged);
    }
}
