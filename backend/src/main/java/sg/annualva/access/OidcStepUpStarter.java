package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import sg.annualva.config.NotDevLocal;

/** Starts a step-up at the identity provider ({@link StepUp#begin}); not under {@code dev-local}, which has none. */
@Component
@NotDevLocal
class OidcStepUpStarter implements StepUpStarter {

    private final StepUp stepUp;

    OidcStepUpStarter(StepUp stepUp) {
        this.stepUp = stepUp;
    }

    @Override
    public String begin(HttpServletRequest request, HttpServletResponse response, SessionUser user, String intent,
            String returnTo) {
        return stepUp.begin(request, response, user, intent, returnTo);
    }
}
