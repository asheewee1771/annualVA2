package sg.annualva.access;

import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * The principal of a callback that completed a step-up, as opposed to a sign-in: the same lean session
 * user (with a fresher {@code auth_time}) plus the step-up it answers, so the callback handlers know
 * where to send the browser.
 */
final class SteppedUpOidcUser extends SignedInOidcUser {

    private final StepUpIntent started;

    SteppedUpOidcUser(SessionUser user, OidcIdToken idToken, StepUpIntent started) {
        super(user, idToken);
        this.started = started;
    }

    StepUpIntent started() {
        return started;
    }
}
