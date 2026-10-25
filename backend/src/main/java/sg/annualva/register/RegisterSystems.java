package sg.annualva.register;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.Need;
import sg.annualva.access.SystemScope;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.CyclePhase;
import sg.annualva.cycle.VaCycle;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The Register table (stories 2 and 12): the Systems the caller may see, each with its SMO/Tech Lead, Cyber Manager,
 * Quarter, whether it is retired and, for a VA POC, whether its SMO/Tech Lead is an Unlinked SMO. A VA POC sees every
 * System; an SMO/Tech Lead or Cyber Manager sees only the Systems their roles name (ADR 0013). The Unlinked SMO flag is
 * read when asked, so it clears the moment the person signs in (ADR 0017), and is shown to the VA POCs only: it is
 * theirs to fix. So is the SMO Unreachable flag, with the date it was set, which is stored, since nothing but the mail
 * relay's answer tells it ({@link SmoReachability}, ADR 0052). A read of the list is not audited: it names no one
 * System's reports and changes nothing.
 *
 * <p>The Register is one standing list whose Quarter column applies to every VA Cycle, so a Cycle's Systems are the
 * Register's, until a newer Cycle opens: from then on the older Cycle keeps its Systems and their Quarters as they were,
 * and an apply changes only the newer one (story 17, ADR 0044). Every reader of a Cycle's Systems asks here.
 */
@Service
public class RegisterSystems {

    /**
     * @param id the System's own id, which every other part of the app refers to it by
     * @param systemId the System ID as the VA POC typed it in the template
     * @param quarter {@code Q1} to {@code Q4}
     * @param unlinkedSmo whether the SMO/Tech Lead's email matches no sign-in yet; null for anyone but a VA POC, and
     *     false for a retired System
     * @param smoUnreachableOn the business date the System was flagged SMO Unreachable (ADR 0052); null while it is not,
     *     for a retired System, and for anyone but a VA POC
     */
    public record SystemView(
            UUID id, String systemId, String name, String smoEmail, String cyberManagerEmail, String quarter,
            boolean retired, @Nullable Boolean unlinkedSmo, @Nullable LocalDate smoUnreachableOn) {}

    /**
     * A System that is still in the Register, for another module's read model.
     *
     * @param id the System's own id
     * @param systemId the System ID as the VA POC typed it
     * @param quarter 1 to 4
     * @param smoEmail the SMO/Tech Lead the Register names for it
     * @param smoUnreachableOn the business date the System was flagged SMO Unreachable (ADR 0052), or null while it is
     *     not; a read model shows it to the VA POCs only, whose flag it is to fix
     */
    public record ActiveSystem(UUID id, String systemId, String name, int quarter, String smoEmail,
            @Nullable LocalDate smoUnreachableOn) {

        /** The same System in another Quarter: the one a VA Cycle kept for it (ADR 0044). */
        ActiveSystem inQuarter(int kept) {
            return new ActiveSystem(id, systemId, name, kept, smoEmail, smoUnreachableOn);
        }
    }

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
    private final CycleCalendar calendar;

    RegisterSystems(RegisterStore store, AccessGuard guard, CycleCalendar calendar) {
        this.store = store;
        this.guard = guard;
        this.calendar = calendar;
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

    /**
     * Every System still in the Register, in System ID order, for a scheduled job, which acts for nobody (the Overdue
     * sweep, ADR 0033). It skips the access guard, so it is never for a request: the architecture test names its callers.
     */
    @Transactional(readOnly = true)
    public List<ActiveSystem> everyActive() {
        return activeWhere(id -> true);
    }

    /**
     * The Systems of one VA Cycle that the caller may reach for this need, in System ID order, each with its Quarter in
     * that Cycle: the Register's, or the ones the Cycle kept when a newer Cycle opened. A System retired since is left out,
     * as everywhere.
     */
    @Transactional(readOnly = true)
    public List<ActiveSystem> active(Need need, int cycleYear) {
        SystemScope scope = guard.scope(need);
        return inCycle(cycleYear, scope::contains);
    }

    /**
     * The same for every System, for a scheduled job, which acts for nobody (the Overdue sweep, the lifecycle). It skips
     * the access guard, so it is never for a request: the architecture test names its callers.
     */
    @Transactional(readOnly = true)
    public List<ActiveSystem> everyActive(int cycleYear) {
        return inCycle(cycleYear, id -> true);
    }

    /**
     * The Quarter of a System in one VA Cycle, for a command that has already asked the access guard about it; nothing
     * when the System is not in that Cycle (added to the Register after a newer Cycle opened, or retired).
     */
    @Transactional(readOnly = true)
    public Optional<Integer> quarterIn(UUID systemId, int cycleYear) {
        return inCycle(cycleYear, systemId::equals).stream().findFirst().map(ActiveSystem::quarter);
    }

    /**
     * Keeps the Systems of every VA Cycle that is no longer the newest to have started, as the Register holds them now,
     * unless they are kept already, so that an apply from now on changes only the newest Cycle (ADR 0044). The lifecycle
     * job calls it when a Cycle opens, and every apply calls it first, so the Cycles are kept even when the apply comes
     * before the job's pass. It takes the apply lock (re-entrant, so an apply that holds it already passes), so an apply
     * never lands between the two.
     *
     * @param now the instant the caller read from the clock; the Cycles are those the app runs on its business date
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void keepOlderCycles(Instant now) {
        store.lockApply();
        olderCycles(calendar.dateOf(now)).forEach(year -> store.freeze(year, now));
    }

    /**
     * The VA Cycles the app runs on this business date that are no longer the newest to have started: each keeps its
     * Systems and Quarters as they were when the newer one opened, so an apply changes nothing in them (ADR 0044).
     */
    Set<Integer> olderCycles(LocalDate today) {
        List<VaCycle> older = new ArrayList<>(calendar.cyclesOn(today).stream()
                .filter(cycle -> cycle.phaseOn(today) != CyclePhase.UPCOMING).toList());
        if (!older.isEmpty()) {
            older.removeLast();
        }
        Set<Integer> years = new LinkedHashSet<>();
        older.forEach(cycle -> years.add(cycle.year()));
        return years;
    }

    /** The Systems of the Cycle whose id passes the test, with their Quarters in it, in System ID order. */
    private List<ActiveSystem> inCycle(int cycleYear, Predicate<UUID> include) {
        Optional<Map<UUID, Integer>> kept = store.frozen(cycleYear);
        return activeWhere(id -> include.test(id) && kept.map(quarters -> quarters.containsKey(id)).orElse(true))
                .stream()
                .map(system -> kept.map(quarters -> quarters.get(system.id())).map(system::inQuarter).orElse(system))
                .toList();
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

    /**
     * The Systems retired before this instant, in System ID order, for the retention job, which acts for nobody and lists
     * a retired System for disposal once its Cycles are disposed of (ticket 49). It skips the access guard, so it is never
     * for a request: the architecture test names its caller.
     */
    @Transactional(readOnly = true)
    public List<UUID> retiredBefore(Instant before) {
        return store.retiredBefore(before).stream().map(ExistingSystem::id).toList();
    }

    /** The Systems still in the Register whose id passes the test, in System ID order. */
    private List<ActiveSystem> activeWhere(Predicate<UUID> include) {
        return store.systems().stream()
                .filter(system -> !system.retired() && include.test(system.id()))
                .map(this::active)
                .toList();
    }

    private ActiveSystem active(ExistingSystem system) {
        return new ActiveSystem(system.id(), system.code(), system.name(), system.quarter(), system.smoEmail(),
                unreachableOn(system));
    }

    /** The business date the System was flagged SMO Unreachable, or null while it is not (ADR 0052). */
    private @Nullable LocalDate unreachableOn(ExistingSystem system) {
        Instant at = system.smoUnreachableAt();
        return at == null ? null : calendar.dateOf(at);
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
                .map(this::active)
                .findFirst()
                .orElseThrow(ResourceNotFoundException::new);
    }

    private SystemView view(ExistingSystem system, boolean vaPoc, Set<String> signedIn) {
        SystemSnapshot snapshot = system.snapshot();
        Boolean unlinked = vaPoc ? !system.retired() && !signedIn.contains(snapshot.smoEmail()) : null;
        LocalDate unreachable = vaPoc && !system.retired() ? unreachableOn(system) : null;
        return new SystemView(system.id(), system.code(), snapshot.name(), snapshot.smoEmail(),
                snapshot.cyberManagerEmail(), snapshot.quarter(), system.retired(), unlinked, unreachable);
    }
}
