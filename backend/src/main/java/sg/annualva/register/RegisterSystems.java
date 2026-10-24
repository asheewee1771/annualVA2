package sg.annualva.register;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.Need;
import sg.annualva.access.SystemScope;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The Register table (stories 2 and 12): the Systems the caller may see, each with its SMO/Tech Lead, Cyber Manager,
 * Quarter, whether it is retired and, for a VA POC, whether its SMO/Tech Lead is an Unlinked SMO. A VA POC sees every
 * System; an SMO/Tech Lead or Cyber Manager sees only the Systems their roles name (ADR 0013). The Unlinked SMO flag is
 * read when asked, so it clears the moment the person signs in (ADR 0017), and is shown to the VA POCs only: it is
 * theirs to fix. A read of the list is not audited: it names no one System's reports and changes nothing.
 */
@Service
public class RegisterSystems {

    /**
     * @param id the System's own id, which every other part of the app refers to it by
     * @param systemId the System ID as the VA POC typed it in the template
     * @param quarter {@code Q1} to {@code Q4}
     * @param unlinkedSmo whether the SMO/Tech Lead's email matches no sign-in yet; null for anyone but a VA POC, and
     *     false for a retired System
     */
    public record SystemView(
            UUID id, String systemId, String name, String smoEmail, String cyberManagerEmail, String quarter,
            boolean retired, @Nullable Boolean unlinkedSmo) {}

    /**
     * A System that is still in the Register, for another module's read model.
     *
     * @param id the System's own id
     * @param systemId the System ID as the VA POC typed it
     * @param quarter 1 to 4
     * @param smoEmail the SMO/Tech Lead the Register names for it
     */
    public record ActiveSystem(UUID id, String systemId, String name, int quarter, String smoEmail) {}

    /**
     * A System with the people the Register names for it, for a consumer that tells them something.
     *
     * @param id the System's own id
     * @param systemId the System ID as the VA POC typed it
     * @param quarter 1 to 4
     */
    public record Named(UUID id, String systemId, String name, String smoEmail, String cyberManagerEmail,
            int quarter) {}

    private final RegisterStore store;
    private final AccessGuard guard;

    RegisterSystems(RegisterStore store, AccessGuard guard) {
        this.store = store;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public List<SystemView> list() {
        SystemScope scope = guard.scope(Need.VIEW);
        List<ExistingSystem> visible = store.systems().stream().filter(system -> scope.contains(system.id())).toList();
        Set<String> signedIn = Set.of();
        if (scope.all()) {
            Set<String> smos = new HashSet<>();
            visible.forEach(system -> smos.add(system.snapshot().smoEmail()));
            signedIn = store.signedIn(smos);
        }
        Set<String> linked = signedIn;
        return visible.stream().map(system -> view(system, scope.all(), linked)).toList();
    }

    /** The Systems still in the Register that the caller may reach for this need, in System ID order. */
    @Transactional(readOnly = true)
    public List<ActiveSystem> active(Need need) {
        SystemScope scope = guard.scope(need);
        return activeWhere(scope::contains);
    }

    /**
     * Every System still in the Register, in System ID order, for a scheduled job, which acts for nobody (the Overdue
     * sweep, ADR 0033). It skips the access guard, so it is never for a request: the architecture test names its callers.
     */
    @Transactional(readOnly = true)
    public List<ActiveSystem> everyActive() {
        return activeWhere(id -> true);
    }

    /**
     * One System as the Register holds it now, retired or not, with the people it names, for the notifications, which
     * address whoever acts next after a move has committed and act for nobody (ADR 0043). It skips the access guard, so
     * it is never for a request: the architecture test names its callers.
     */
    @Transactional(readOnly = true)
    public Optional<Named> named(UUID systemId) {
        return store.system(systemId).map(system -> new Named(system.id(), system.code(), system.name(),
                system.smoEmail(), system.cyberManagerEmail(), system.quarter()));
    }

    /** The Systems still in the Register whose id passes the test, in System ID order. */
    private List<ActiveSystem> activeWhere(Predicate<UUID> include) {
        return store.systems().stream()
                .filter(system -> !system.retired() && include.test(system.id()))
                .map(system -> new ActiveSystem(system.id(), system.code(), system.name(), system.quarter(),
                        system.smoEmail()))
                .toList();
    }

    /**
     * One System still in the Register, for a command that addresses it. The access guard answers first (ADR 0013), so a
     * System the caller may not reach for this need, one that does not exist and one that is retired are all "not found".
     */
    @Transactional(readOnly = true)
    public ActiveSystem active(UUID systemId, Need need) {
        guard.requireSystem(systemId, need);
        return store.systems().stream()
                .filter(system -> !system.retired() && system.id().equals(systemId))
                .map(system -> new ActiveSystem(system.id(), system.code(), system.name(), system.quarter(),
                        system.smoEmail()))
                .findFirst()
                .orElseThrow(ResourceNotFoundException::new);
    }

    private static SystemView view(ExistingSystem system, boolean vaPoc, Set<String> signedIn) {
        SystemSnapshot snapshot = system.snapshot();
        Boolean unlinked = vaPoc ? !system.retired() && !signedIn.contains(snapshot.smoEmail()) : null;
        return new SystemView(system.id(), system.code(), snapshot.name(), snapshot.smoEmail(),
                snapshot.cyberManagerEmail(), snapshot.quarter(), system.retired(), unlinked);
    }
}
