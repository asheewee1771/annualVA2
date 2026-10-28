package sg.annualva.access;

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
 * sent to {@link StepUpStarter}'s fresh sign-in (the identity provider's, or the local confirmation under
 * {@code dev-local}) and comes back to {@code returnTo}. Nothing the command asked for is run or remembered here;
 * the person repeats it.
 */
@RestController
public class StepUpController {

    private final StepUpStarter starter;

    StepUpController(StepUpStarter starter) {
        this.starter = starter;
    }

    @GetMapping(StepUpRequiredException.START_PATH)
    @PreAuthorize("isAuthenticated()")
    public void start(@RequestParam @Pattern(regexp = StepUp.INTENT_PATTERN) String intent,
            @RequestParam(required = false) @Nullable String returnTo, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!(authentication.getPrincipal() instanceof SessionUser user)) {
            throw new AccessDeniedException("Not a signed-in person");
        }
        response.sendRedirect(starter.begin(request, response, user, intent, StepUp.safeReturnPath(returnTo)));
    }
}
