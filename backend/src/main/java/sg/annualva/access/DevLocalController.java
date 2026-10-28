package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import sg.annualva.config.DevLocal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of the {@code dev-local} profile that are not the sign-in itself (ticket 63, ADR 0058): the list of the
 * six personas, which the front end reads to offer its picker (a {@code 404} anywhere else is how it knows not to), and
 * the local confirmation of a step-up, which takes the place of the round trip to the identity provider. Only under
 * {@code dev-local}. A step-up is started at the path the SPA already follows ({@link StepUpController}, which asks
 * {@link DevLocalStepUp} to).
 */
@RestController
@DevLocal
public class DevLocalController {

    /** @param intent the action that was started, a code */
    public record Confirmation(@NotNull @Pattern(regexp = StepUp.INTENT_PATTERN) String intent) {}

    private final DevLocalPersonas personas;
    private final DevLocalStepUp stepUp;

    DevLocalController(DevLocalPersonas personas, DevLocalStepUp stepUp) {
        this.personas = personas;
        this.stepUp = stepUp;
    }

    /** Anonymous by design (listed in SecurityConfig.PUBLIC_PATHS): the signed-out screen offers the picker. */
    @GetMapping("/api/dev/personas")
    @PreAuthorize("permitAll()")
    public List<DevLocalPersonas.Persona> personas() {
        return personas.all();
    }

    @PostMapping("/api/dev/step-up")
    @PreAuthorize("isAuthenticated()")
    public Map<String, String> confirm(@Valid @RequestBody Confirmation confirmation, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        String returnTo = stepUp.confirm(request, response, signedIn(authentication), confirmation.intent());
        return Map.of("returnTo", returnTo);
    }

    private static SessionUser signedIn(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof SessionUser user)) {
            throw new AccessDeniedException("Not a signed-in person");
        }
        return user;
    }
}
