package sg.annualva.access;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

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
        @Positive int anonymousSessionsPerMinute) {}
