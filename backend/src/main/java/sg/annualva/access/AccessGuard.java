package sg.annualva.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Per-System ownership (IM8 ac-1, as-7). Every service path that addresses a System, or anything
 * that belongs to one such as a VA Submission, a Scan Report file or an export, asks this guard
 * first. A caller without scope over an existing System gets {@link ResourceNotFoundException},
 * the same answer as for one that does not exist, so nobody can learn which Systems exist. The
 * answer is built from the grants the request resolved from the role store; a call with no
 * resolved roles has no scope at all.
 */
@Component
public class AccessGuard {

    /** The Systems the caller may reach for this need. */
    public SystemScope scope(Need need) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof RoleAuthentication caller)) {
            return SystemScope.NONE;
        }
        return switch (need) {
            case VIEW -> caller.holds(Role.VA_POC)
                    ? everything()
                    : new SystemScope(false, union(caller.systemsFor(Role.SMO), caller.systemsFor(Role.CYBER_MANAGER)));
            case SUBMIT -> new SystemScope(false, caller.systemsFor(Role.SMO));
            case REVIEW -> new SystemScope(false, caller.systemsFor(Role.CYBER_MANAGER));
            case ENDORSE -> caller.holds(Role.VA_POC) ? everything() : SystemScope.NONE;
            case AUDIT -> caller.holds(Role.VA_POC)
                    ? everything()
                    : new SystemScope(false, caller.systemsFor(Role.CYBER_MANAGER));
        };
    }

    /** Passes silently when the caller has scope over the System; otherwise it is "not found". */
    public void requireSystem(UUID systemId, Need need) {
        if (!scope(need).contains(systemId)) {
            throw new ResourceNotFoundException();
        }
    }

    private static SystemScope everything() {
        return new SystemScope(true, Set.of());
    }

    private static Set<UUID> union(Set<UUID> first, Set<UUID> second) {
        var all = new HashSet<>(first);
        all.addAll(second);
        return all;
    }
}
