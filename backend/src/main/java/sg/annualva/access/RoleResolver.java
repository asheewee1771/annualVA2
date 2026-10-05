package sg.annualva.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads a signed-in person's grants from the role store, once per request (ADR 0011). Marks the
 * grants used at most once a day, so the lookup stays a read on almost every request and the
 * 90-day lapse has the data it needs.
 */
@Component
class RoleResolver {

    private static final Duration USED_GRANULARITY = Duration.ofDays(1);

    private final RoleGrantStore grants;
    private final Clock clock;

    RoleResolver(RoleGrantStore grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    RoleAuthentication resolve(SessionUser user) {
        Instant now = clock.instant();
        List<RoleGrant> active = grants.activeFor(user.id(), now);
        List<UUID> stale = active.stream()
                .filter(grant -> grant.lastUsedAt() == null || grant.lastUsedAt().isBefore(now.minus(USED_GRANULARITY)))
                .map(RoleGrant::id)
                .toList();
        if (!stale.isEmpty()) {
            grants.markUsed(stale, now);
        }
        return new RoleAuthentication(user, active);
    }

    /**
     * The roles this person holds now, as one audit entry records them: highest privilege first,
     * comma-separated, or null for none. A read only, for events outside a request's own resolution.
     */
    @Nullable String rolesOf(UUID userId) {
        List<RoleGrant> active = grants.activeFor(userId, clock.instant());
        return RoleAuthentication.audited(Arrays.stream(Role.values())
                .filter(role -> active.stream().anyMatch(grant -> grant.role() == role))
                .toList());
    }
}
