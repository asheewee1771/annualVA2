package sg.annualva.access;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The signed-in person behind the current request, for a command that has to record who it was done by (a Register
 * preview remembers its uploader). The id is the app_user id, never an email or a name (lm-19).
 */
@Component
public class CurrentUser {

    /** The caller's user id; a request that has not resolved a person is refused. */
    public UUID id() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof RoleAuthentication caller) {
            return caller.getPrincipal().id();
        }
        throw new AccessDeniedException("No signed-in person");
    }
}
