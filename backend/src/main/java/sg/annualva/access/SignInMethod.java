package sg.annualva.access;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * How a person starts a sign-in: the one thing that differs between profiles. {@link OidcLogin} is the organisation's
 * single sign-on (every profile but {@code dev-local}); {@link DevLocalSignIn} is the persona picker of a developer's
 * machine. Everything after the identity is known (the sign-in rules, the session rules, sign-out, the limits) is the
 * same code for both and lives in {@link SignInSecurity}.
 */
interface SignInMethod {

    /** Adds this method's endpoints and filters to the one filter chain. */
    void apply(HttpSecurity http) throws Exception;
}
