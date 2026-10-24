package sg.annualva.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;

/**
 * The retention job's listing (story 87): what is due for disposal on today's business date, written as a DRAFT disposal
 * list for a VA POC to approve. A pass that finds exactly what the waiting DRAFT holds keeps it, so the list a VA POC is
 * reading does not change under them; one that finds anything else supersedes it with a new DRAFT, and the approval of
 * the old one is refused. While an approved list waits for the executor nothing is listed.
 */
@Service
public class DisposalPlanner {

    private final DisposalStore store;
    private final CycleCalendar calendar;
    private final RegisterSystems systems;
    private final AuditLog audit;
    private final Clock clock;

    DisposalPlanner(DisposalStore store, CycleCalendar calendar, RegisterSystems systems, AuditLog audit, Clock clock) {
        this.systems = systems;
        this.store = store;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
    }

    /** One pass of the listing. */
    @Transactional
    public void plan() {
        store.lock();
        Optional<DisposalStore.ListRow> open = store.open();
        if (open.isPresent() && open.get().status() == ListStatus.APPROVED) {
            return;
        }
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        Due due = due(now, today);
        if (open.isPresent()) {
            DisposalStore.ListRow waiting = open.get();
            if (Objects.equals(waiting.cutoff(), due.cutoff()) && store.items(waiting.id()).equals(due.items())) {
                return;
            }
            store.supersede(waiting.id());
        }
        if (due.items().isEmpty()) {
            return;
        }
        UUID id = store.insert(today, now, due.cutoff(), due.items());
        audit.record(AuditEvent.of(AuditAction.DISPOSAL_LISTED, AuditOutcome.SUCCESS)
                .with(DetailKey.DISPOSAL_LIST_ID, id).with(DetailKey.COUNT, due.items().size()));
    }

    /**
     * What is due on this business date, in the order it is listed.
     *
     * @param cutoff before which the due Cycles' records were written; null when the list disposes of no Cycle
     */
    private record Due(@Nullable Instant cutoff, List<DisposalItem> items) {}

    private Due due(Instant now, LocalDate today) {
        List<Integer> cycles = calendar.cyclesOn(today).stream()
                .filter(cycle -> cycle.closedAt() != null && retentionEnded(cycle.closedAt(), today))
                .map(VaCycle::year).toList();
        Instant cutoff = cycles.isEmpty() ? null : calendar.startOf(nextCycleStart(cycles.getLast()));
        List<DisposalItem> items = new ArrayList<>(cycleItems(cycles));
        if (cutoff != null) {
            items.addAll(retiredSystems(cycles, cutoff));
        }
        store.supersededDue().stream().filter(file -> !cycles.contains(file.cycle())).forEach(file -> items.add(
                new DisposalItem(ItemKind.SUPERSEDED_FILE, file.id(), file.systemId(), file.cycle(),
                        DisposalReason.SUPERSEDED_AFTER_ENDORSEMENT, null)));
        store.expiredExports().forEach(export -> items.add(
                new DisposalItem(ItemKind.EXPORT, export, null, null, DisposalReason.EXPORT_EXPIRED, null)));
        long entries = cutoff == null ? 0 : store.auditBefore(cutoff);
        if (entries > 0) {
            items.add(new DisposalItem(ItemKind.AUDIT_RANGE, null, null, cycles.getLast(),
                    DisposalReason.CYCLE_RETENTION_ENDED, entries));
        }
        boolean disposesOfCycles = items.stream()
                .anyMatch(item -> item.kind() == ItemKind.CYCLE || item.kind() == ItemKind.AUDIT_RANGE);
        return new Due(disposesOfCycles ? cutoff : null, items);
    }

    /** Each due Cycle that still holds anything: its VA Submissions, their files, and the Cycle's other records. */
    private List<DisposalItem> cycleItems(List<Integer> cycles) {
        List<DisposalItem> items = new ArrayList<>();
        for (int year : cycles) {
            if (!store.cycleHolds(year)) {
                continue;
            }
            store.submissionsOf(year).forEach(sub -> items.add(new DisposalItem(ItemKind.SUBMISSION, sub.id(),
                    sub.systemId(), year, DisposalReason.CYCLE_RETENTION_ENDED, null)));
            store.filesOf(year).forEach(file -> items.add(new DisposalItem(ItemKind.FILE, file.id(), file.systemId(),
                    year, DisposalReason.CYCLE_RETENTION_ENDED, null)));
            items.add(new DisposalItem(ItemKind.CYCLE, null, null, year, DisposalReason.CYCLE_RETENTION_ENDED, null));
        }
        return items;
    }

    /** The Systems retired before the cutoff that have nothing left outside the due Cycles. */
    private List<DisposalItem> retiredSystems(List<Integer> cycles, Instant cutoff) {
        return systems.retiredBefore(cutoff).stream()
                .filter(system -> cycles.containsAll(store.cyclesOfSystem(system)))
                .map(system -> new DisposalItem(ItemKind.SYSTEM, system, system, null, DisposalReason.SYSTEM_RETIRED,
                        null))
                .toList();
    }

    /**
     * A Cycle's records are kept for the Cycle plus 3 years: through the third anniversary of the business date it closed
     * on, and due the day after (ADR 0049, provisional).
     */
    private boolean retentionEnded(Instant closedAt, LocalDate today) {
        return calendar.dateOf(closedAt).plusYears(3).isBefore(today);
    }

    /**
     * The first day of the Cycle after this one: what was written before it belongs to this Cycle or an earlier one, so
     * nothing of a later Cycle is disposed of early (the weeks a Cycle stays open after its Q4 wait for the next Cycle).
     */
    private LocalDate nextCycleStart(int year) {
        return calendar.cycle(year + 1).orElseThrow().startsOn();
    }
}
