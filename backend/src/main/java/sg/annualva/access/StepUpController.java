package sg.annualva.access;

import org.springframework.context.annotation.Profile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.StepUpRequiredException;

/**
 * Where the browser goes when a sensitive command answered {@code step-up-required}: the person is
 * sent to the identity provider for a fresh sign-in and comes back to {@code returnTo}. Nothing the
 * command asked for is run or remembered here; the person repeats it.
 */
@RestController
@Profile("!dev-local")
public class StepUpController {

    private final StepUp stepUp;

    StepUpController(StepUp stepUp) {
        this.stepUp = stepUp;
    }

    @GetMapping(StepUpRequiredException.START_PATH)
    @PreAuthorize("isAuthenticated()")
    public void start(@RequestParam @Pattern(regexp = StepUp.INTENT_PATTERN) String intent,
            @RequestParam(required = false) @Nullable String returnTo, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!(authentication.getPrincipal() instanceof SessionUser user)) {
            throw new AccessDeniedException("Not a signed-in person");
        }
        response.sendRedirect(stepUp.begin(request, response, user, intent, StepUp.safeReturnPath(returnTo)));
    }
}
