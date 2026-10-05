package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * The one success and failure handler of the OIDC callback. It does no work of its own: a step-up's
 * typed outcome ({@link SteppedUpOidcUser}, {@link StepUpRefusedException}) goes to the step-up
 * handlers, everything else to the sign-in handlers.
 */
@Component
class SignInCallbackHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final SignInSuccessHandler signInSuccess;
    private final SignInFailureHandler signInFailure;
    private final StepUpSuccessHandler stepUpSuccess;
    private final StepUpFailureHandler stepUpFailure;

    SignInCallbackHandler(SignInSuccessHandler signInSuccess, SignInFailureHandler signInFailure,
            StepUpSuccessHandler stepUpSuccess, StepUpFailureHandler stepUpFailure) {
        this.signInSuccess = signInSuccess;
        this.signInFailure = signInFailure;
        this.stepUpSuccess = stepUpSuccess;
        this.stepUpFailure = stepUpFailure;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        if (authentication.getPrincipal() instanceof SteppedUpOidcUser) {
            stepUpSuccess.onAuthenticationSuccess(request, response, authentication);
        } else {
            signInSuccess.onAuthenticationSuccess(request, response, authentication);
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        if (exception instanceof StepUpRefusedException refused) {
            stepUpFailure.onRefused(request, response, refused);
        } else {
            signInFailure.onAuthenticationFailure(request, response, exception);
        }
    }
}
