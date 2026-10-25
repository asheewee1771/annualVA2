package sg.annualva.access;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import sg.annualva.boundary.IdentityProvider.Identity;

/**
 * Sign-in and session rules (prefix {@code annualva.sign-in}).
 *
 * @param requireMfaClaim refuse a sign-in whose token shows no multi-factor evidence (ac-2); set per
 *     environment from what the identity provider emits, never silently
 * @param requireVerifiedEmail refuse a sign-in whose token carries an email the provider does not assert
 *     as verified ({@code email_verified}); the email links people to the System Register, so it must be
 *     trustworthy. Set per environment from what the provider emits, never silently
 * @param mfaAmrValues {@code amr} values that count as multi-factor evidence
 * @param mfaAcrValues {@code acr} values that count as multi-factor evidence
 * @param idleTimeout as-11: the session ends after this long without a request
 * @param absoluteTimeout as-11: the session ends this long after sign-in regardless of activity
 * @param anonymousSessionsPerMinute as-4: how many sessions one client address may start per minute without
 *     being signed in (sign-in start and CSRF bootstrap); each one is a row in the session store
 * @param identityCheckInterval ac-3 (ticket 47): how often a signed-in session asks the identity provider whether the
 *     person has been disabled there since; the sign-in itself counts as the first check
 */
@ConfigurationProperties("annualva.sign-in")
@Validated
public record SignInProperties(
        @DefaultValue("true") boolean requireMfaClaim,
        @DefaultValue("true") boolean requireVerifiedEmail,
        @DefaultValue({"mfa", "otp", "hwk"}) List<String> mfaAmrValues,
        @DefaultValue List<String> mfaAcrValues,
        @NotNull Duration idleTimeout,
        @NotNull Duration absoluteTimeout,
        @Positive int anonymousSessionsPerMinute,
        @DefaultValue("5m") @NotNull Duration identityCheckInterval) {

    /** True when the token shows multi-factor evidence by {@code amr} or {@code acr}. */
    boolean evidencesMfa(Identity identity) {
        boolean byAmr = identity.amr().stream().anyMatch(mfaAmrValues::contains);
        boolean byAcr = identity.acr() != null && mfaAcrValues.contains(identity.acr());
        return byAmr || byAcr;
    }
}
