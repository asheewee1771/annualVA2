package sg.annualva.access;

import java.util.Set;
import java.util.UUID;

/**
 * Which Systems a caller may reach for one {@link Need}: every System (a VA POC), or exactly the
 * listed ones. A list endpoint filters by it; a single-System path asks {@link #contains}.
 */
public record SystemScope(boolean all, Set<UUID> ids) {

    public static final SystemScope NONE = new SystemScope(false, Set.of());

    public SystemScope {
        ids = Set.copyOf(ids);
    }

    public boolean contains(UUID systemId) {
        return all || ids.contains(systemId);
    }
}
