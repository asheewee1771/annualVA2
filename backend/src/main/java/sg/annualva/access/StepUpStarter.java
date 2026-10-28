package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * How a step-up starts: the one thing that differs between profiles, as {@link SignInMethod} is for a sign-in.
 * {@link OidcStepUpStarter} asks the identity provider for a fresh sign-in (every profile but {@code dev-local});
 * {@link DevLocalStepUp} asks the developer to confirm. The sensitive command's own check, {@link StepUp#require}, is
 * the same for both.
 */
interface StepUpStarter {

    /**
     * Starts a step-up for the signed-in person.
     *
     * @param returnTo a same-origin path of safe characters only
     * @return where to send the browser
     */
    String begin(HttpServletRequest request, HttpServletResponse response, SessionUser user, String intent,
            String returnTo);
}
