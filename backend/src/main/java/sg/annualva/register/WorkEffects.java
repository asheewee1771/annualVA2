package sg.annualva.register;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.Role;
import sg.annualva.access.RoleHolders;
import sg.annualva.cycle.CycleCalendar;

/**
 * What each row of a Register change preview does to in-flight work (stories 5 to 7, ADR 0049), the one place the rule
 * is written: the upload classifies its rows with it ({@link #preview}), and the apply classifies the stored rows again
 * with the work it holds ({@link #held}), so it applies exactly what was shown.
 *
 * <ul>
 *   <li>A RETIRE of a System with in-flight work is Will withdraw. It is applied once the VA POC confirms each VA
 *       Submission (story 6), and held back while any of their files is still being checked (decision 2026-10-18).
 *   <li>An UPDATE that moves the System to another Quarter is Will move when it has in-flight work in a VA Cycle that
 *       follows the Register (an older Cycle keeps its Quarters, ADR 0044); one that changes its SMO/Tech Lead or Cyber
 *       Manager is Will transfer when it has in-flight work in any Cycle. Both are held back in Release 1, until the
 *       mid-cycle tickets of Release 2, with one exception (decided 2026-10-26): a transfer that only replaces people
 *       whose role for the System is suspended (ADR 0056) is applied, because the hold-back would otherwise leave the
 *       System with nobody to act, and a VA POC's restore the only exit.
 *   <li>A transfer is also held back when the new SMO/Tech Lead or Cyber Manager acted at, or was assigned to, another
 *       step of an in-flight VA Submission (story 58): Release 2 lifts the transfer block, not this one, and neither
 *       does the suspension exception.
 * </ul>
 *
 * A row keeps its outcome; what holds it back is its reasons, and a preview with any reason cannot be applied. A row
 * that touches no in-flight work, an ADD, a NO_CHANGE and a row Blocked by the file itself have no effect.
 */
@Component
class WorkEffects {

    private final InFlightWork work;
    private final RegisterSystems systems;
    private final CycleCalendar calendar;
    private final RoleHolders roleHolders;

    WorkEffects(InFlightWork work, RegisterSystems systems, CycleCalendar calendar, RoleHolders roleHolders) {
        this.work = work;
        this.systems = systems;
        this.calendar = calendar;
        this.roleHolders = roleHolders;
    }

    /** The rows with their effects on the in-flight work as it is now, holding nothing: for an upload. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<PreviewRow> preview(List<PreviewRow> rows, Instant now) {
        Set<UUID> touched = systemsOf(rows);
        return classify(rows, work.of(touched), systems.olderCycles(calendar.dateOf(now)),
                roleHolders.suspendedRolesOn(touched));
    }

    /**
     * The rows with their effects on the in-flight work, which is held until the transaction ends ({@link InFlightWork#hold}):
     * for an apply.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<PreviewRow> held(List<PreviewRow> rows, Instant now) {
        Set<UUID> touched = systemsOf(rows);
        return classify(rows, work.hold(touched, now), systems.olderCycles(calendar.dateOf(now)),
                roleHolders.suspendedRolesOn(touched));
    }

    /** Withdraws the VA Submissions a retired row names; how many it withdrew. */
    @Transactional(propagation = Propagation.MANDATORY)
    int withdraw(PreviewRow retired, UUID previewId, Instant now) {
        return work.withdraw(retired.inFlight().stream().map(PreviewRow.InFlight::submissionId).toList(), previewId, now);
    }

    /** The Systems whose in-flight work the rows could touch: those a RETIRE or an UPDATE names. */
    private static Set<UUID> systemsOf(List<PreviewRow> rows) {
        Set<UUID> systems = new LinkedHashSet<>();
        for (PreviewRow row : rows) {
            if ((row.outcome() == Outcome.RETIRE || row.outcome() == Outcome.UPDATE) && row.existingSystemId() != null) {
                systems.add(row.existingSystemId());
            }
        }
        return systems;
    }

    /**
     * The rows with their effects on the in-flight work given, each row's earlier effects replaced: the same rows give the
     * same answer however often they are classified.
     *
     * @param work the in-flight VA Submissions of the rows' Systems, by System
     * @param olderCycles the VA Cycles that keep their Systems as they were when a newer one opened, so a move changes
     *     nothing in them
     * @param suspended by System, the Register roles whose holder is suspended, which a transfer may replace mid-flight
     */
    private static List<PreviewRow> classify(List<PreviewRow> rows, Map<UUID, List<InFlightWork.Work>> work,
            Set<Integer> olderCycles, Map<UUID, Set<Role>> suspended) {
        return rows.stream().map(row -> classify(row, work, olderCycles, suspended)).toList();
    }

    private static PreviewRow classify(PreviewRow row, Map<UUID, List<InFlightWork.Work>> work,
            Set<Integer> olderCycles, Map<UUID, Set<Role>> suspended) {
        if (row.outcome() != Outcome.RETIRE && row.outcome() != Outcome.UPDATE) {
            return row;
        }
        List<InFlightWork.Work> inFlight = work.getOrDefault(Objects.requireNonNull(row.existingSystemId()), List.of());
        List<Effect> effects = new ArrayList<>();
        List<BlockReason> reasons = new ArrayList<>();
        if (!inFlight.isEmpty() && row.outcome() == Outcome.RETIRE) {
            effects.add(Effect.WILL_WITHDRAW);
            if (inFlight.stream().anyMatch(InFlightWork.Work::filesScanning)) {
                reasons.add(BlockReason.FILES_SCANNING);
            }
        } else if (!inFlight.isEmpty()) {
            SystemSnapshot before = Objects.requireNonNull(row.before(), "an UPDATE has a before");
            SystemSnapshot after = Objects.requireNonNull(row.after(), "an UPDATE has an after");
            if (before.movesIn(after) && inFlight.stream().anyMatch(item -> !olderCycles.contains(item.cycle()))) {
                effects.add(Effect.WILL_MOVE);
                reasons.add(BlockReason.MOVE_IN_FLIGHT);
            }
            if (before.transfersIn(after)) {
                effects.add(Effect.WILL_TRANSFER);
                if (!replacesOnlySuspended(before, after, suspended.getOrDefault(row.existingSystemId(), Set.of()))) {
                    reasons.add(BlockReason.TRANSFER_IN_FLIGHT);
                }
                if (inFlight.stream().anyMatch(item -> twoSteps(before, after, item))) {
                    reasons.add(BlockReason.SEGREGATION_OF_DUTIES);
                }
            }
        }
        List<PreviewRow.InFlight> touched = effects.isEmpty() ? List.of() : inFlight.stream()
                .map(item -> new PreviewRow.InFlight(item.submissionId(), item.cycle(), item.state())).toList();
        return new PreviewRow(row.sheetRow(), row.systemId(), row.existingSystemId(), row.outcome(), reasons,
                row.unlinkedSmo(), row.before(), row.after(), effects, touched);
    }

    /**
     * Whether every person the transfer replaces holds a suspended role for the System (the one exception to the
     * hold-back, ADR 0049 item 2): the SMO/Tech Lead it changes is suspended as SMO/Tech Lead, and the Cyber Manager it
     * changes is suspended as Cyber Manager.
     *
     * @param suspended the System's Register roles whose holder is suspended
     */
    private static boolean replacesOnlySuspended(SystemSnapshot before, SystemSnapshot after, Set<Role> suspended) {
        return (!before.changesSmoIn(after) || suspended.contains(Role.SMO))
                && (!before.changesCyberManagerIn(after) || suspended.contains(Role.CYBER_MANAGER));
    }

    /**
     * Whether the change gives one person two steps of the VA Submission (story 58): the new SMO/Tech Lead reviewed or
     * endorsed it, or was its Cyber Manager; or the new Cyber Manager started, submitted or endorsed it, or was its SMO/Tech
     * Lead. Emails are lower-case on both sides.
     */
    private static boolean twoSteps(SystemSnapshot before, SystemSnapshot after, InFlightWork.Work work) {
        String smo = after.smoEmail();
        String manager = after.cyberManagerEmail();
        boolean smoElsewhere = smo.equals(before.cyberManagerEmail()) || work.actedAtReview().contains(smo)
                || work.actedAtEndorse().contains(smo);
        boolean managerElsewhere = manager.equals(before.smoEmail()) || work.actedAtSubmit().contains(manager)
                || work.actedAtEndorse().contains(manager);
        return smoElsewhere || managerElsewhere;
    }

    /** The VA Submissions the rows would withdraw, each of which the VA POC confirms (story 6). */
    static Set<UUID> withdrawals(List<PreviewRow> rows) {
        Set<UUID> withdrawn = new LinkedHashSet<>();
        rows.stream().filter(row -> row.effects().contains(Effect.WILL_WITHDRAW))
                .forEach(row -> row.inFlight().forEach(item -> withdrawn.add(item.submissionId())));
        return withdrawn;
    }

    /**
     * Whether two classifications of the same rows say the same about in-flight work: the same reasons and effects on the
     * same VA Submissions. A state that moved on in between (a Draft submitted, say) is not a difference: what the VA POC
     * confirmed is the VA Submission.
     */
    static boolean sameEffects(List<PreviewRow> shown, List<PreviewRow> now) {
        return shown.stream().map(Shown::of).toList().equals(now.stream().map(Shown::of).toList());
    }

    /** What a row says about in-flight work, for comparing two classifications of it. */
    private record Shown(List<BlockReason> reasons, List<Effect> effects, List<UUID> submissions) {

        static Shown of(PreviewRow row) {
            return new Shown(row.reasons(), row.effects(),
                    row.inFlight().stream().map(PreviewRow.InFlight::submissionId).toList());
        }
    }
}
