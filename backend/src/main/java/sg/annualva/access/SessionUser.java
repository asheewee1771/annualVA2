package sg.annualva.access;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The signed-in person as kept in the server-side session: the user id (the only identifier that
 * may appear in logs), what the header shows, and the two instants the session rules need. No
 * token is kept.
 *
 * @param authTime when the identity provider authenticated the person (step-up freshness)
 * @param signedInAt when this session was established (absolute limit)
 */
public record SessionUser(UUID id, String email, @Nullable String displayName, Instant authTime, Instant signedInAt)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The principal name: the user id, which also indexes the session store for the one-session rule. */
    @Override
    public String getName() {
        return id.toString();
    }
}
