package sg.annualva.access;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.config.AnnualVaProperties;

/**
 * What the SPA asks on load: is there a session, who is signed in, what the shell must show and
 * when the session ends. Anonymous by design (listed in SecurityConfig.PUBLIC_PATHS) so the
 * signed-out screen can show the classification banner. Roles are those the role store grants this
 * request, highest privilege first, never anything the token claimed.
 */
@RestController
public class SessionController {

    private final AnnualVaProperties properties;
    private final SignInProperties signIn;

    SessionController(AnnualVaProperties properties, SignInProperties signIn) {
        this.properties = properties;
        this.signIn = signIn;
    }

    @GetMapping("/api/session")
    @PreAuthorize("permitAll()")
    public Map<String, Object> session(@Nullable Authentication authentication) {
        var view = new LinkedHashMap<String, Object>();
        view.put("classification", properties.classification().banner());
        if (authentication instanceof RoleAuthentication caller) {
            SessionUser user = caller.getPrincipal();
            view.put("authenticated", true);
            view.put("uuid", user.id().toString());
            view.put("email", user.email());
            view.put("name", user.displayName());
            view.put("roles", caller.roles().stream().map(Role::name).toList());
            view.put("idleTimeoutSeconds", signIn.idleTimeout().toSeconds());
            view.put("absoluteExpiresAt", user.signedInAt().plus(signIn.absoluteTimeout()).toString());
        } else {
            view.put("authenticated", false);
        }
        return view;
    }
}
