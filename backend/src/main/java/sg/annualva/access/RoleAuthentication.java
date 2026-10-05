package sg.annualva.access;

import java.io.Serial;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The caller as one request sees them: the session's {@link SessionUser} plus the role grants that
 * count right now, read from the role store at the start of the request (ADR 0011). It is set on a
 * request-scoped security context and never saved to the session, so nothing about roles outlives
 * the request: a grant revoked, lapsed, suspended or expired between two calls is gone on the second.
 * Authorities are one {@code ROLE_*} per role held; which Systems a role covers is answered by the
 * {@link AccessGuard} from the grants kept here.
 */
public final class RoleAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final SessionUser user;
    private final List<RoleGrant> grants;

    RoleAuthentication(SessionUser user, List<RoleGrant> grants) {
        super(authoritiesOf(grants));
        this.user = user;
        this.grants = List.copyOf(grants);
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authoritiesOf(List<RoleGrant> grants) {
        return Arrays.stream(Role.values())
                .filter(role -> grants.stream().anyMatch(grant -> grant.role() == role))
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
                .toList();
    }

    @Override
    public SessionUser getPrincipal() {
        return user;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    /** The roles held, each once, highest privilege first. */
    public List<Role> roles() {
        return Arrays.stream(Role.values()).filter(this::holds).toList();
    }

    /** The roles as an audit entry records them (highest privilege first, comma-separated), or null for none. */
    static @Nullable String audited(List<Role> roles) {
        return roles.isEmpty() ? null : roles.stream().map(Role::name).collect(Collectors.joining(","));
    }

    boolean holds(Role role) {
        return grants.stream().anyMatch(grant -> grant.role() == role);
    }

    /** The Systems this person holds the role for (empty for the organisation-wide roles). */
    Set<UUID> systemsFor(Role role) {
        var systems = new LinkedHashSet<UUID>();
        grants.stream()
                .filter(grant -> grant.role() == role && grant.systemId() != null)
                .forEach(grant -> systems.add(grant.systemId()));
        return systems;
    }
}
