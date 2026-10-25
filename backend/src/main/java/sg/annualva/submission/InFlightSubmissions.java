package sg.annualva.submission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.register.InFlightWork;

/**
 * The in-flight work a Register change touches (stories 5 to 7, ADR 0049), answered where the VA Submissions are kept: the
 * VA Submissions of a System that are not in a final state, whether a file of each is still being checked (the one rule,
 * {@link SubmitReadiness#filesScanning}), and who acted at each step of it. The withdraw is this module's move to
 * Withdrawn: it clears the Overdue flag ({@link SubmissionStore#OVERDUE_AFTER_MOVE}), is audited as the apply's caller's
 * and publishes a {@link SubmissionEvent}, so the notifications tell the SMO/Tech Lead and clear a VA POC pair as any move
 * does. The callers are the Register's preview and apply, made by a VA POC, whose scope is every System.
 */
@Component
class InFlightSubmissions implements InFlightWork {

    private final SubmissionStore store;
    private final SubmitReadiness readiness;
    private final CycleCalendar calendar;
    private final AuditLog audit;
    private final AuditActor actor;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;

    InFlightSubmissions(SubmissionStore store, SubmitReadiness readiness, CycleCalendar calendar, AuditLog audit,
            AuditActor actor, CurrentUser user, ApplicationEventPublisher events) {
        this.store = store;
        this.readiness = readiness;
        this.calendar = calendar;
        this.audit = audit;
        this.actor = actor;
        this.user = user;
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<Work>> of(Collection<UUID> systemIds) {
        return work(store.inFlight(systemIds));
    }

    /**
     * Holds every VA Cycle the app runs against a start (a start holds its Cycle shared), in year order, then the in-flight
     * VA Submissions as a move holds them, in id order: the order the close takes them in too (ADR 0044 item 6).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<Work>> hold(Collection<UUID> systemIds, Instant now) {
        if (systemIds.isEmpty()) {
            // Nothing is retired or updated (an apply of additions only), so no work can be touched.
            return Map.of();
        }
        calendar.cyclesOn(calendar.dateOf(now)).forEach(cycle -> store.lockCycle(cycle.year()));
        return work(store.holdInFlight(systemIds));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int withdraw(Collection<UUID> submissionIds, UUID previewId, Instant now) {
        List<SubmissionStore.Finished> withdrawn = store.withdraw(submissionIds, calendar.dateOf(now));
        for (SubmissionStore.Finished item : withdrawn) {
            SubmissionState from = Objects.requireNonNull(item.from(), "a withdrawn VA Submission had a state");
            audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_WITHDRAWN, AuditOutcome.SUCCESS))
                    .onSystem(item.systemId()).onSubmission(item.id())
                    .with(DetailKey.STATE_FROM, from.name())
                    .with(DetailKey.STATE_TO, SubmissionState.WITHDRAWN.name())
                    .with(DetailKey.PREVIEW_ID, previewId));
            // A final state waits on no window, so nothing is due.
            events.publishEvent(new SubmissionEvent(item.id(), item.systemId(), from, SubmissionState.WITHDRAWN,
                    user.id(), now, null));
        }
        return withdrawn.size();
    }

    private Map<UUID, List<Work>> work(List<SubmissionStore.InFlight> inFlight) {
        Map<UUID, Map<SubmissionStep, Set<String>>> acted = new HashMap<>();
        store.actedBy(inFlight.stream().map(SubmissionStore.InFlight::id).toList())
                .forEach(item -> acted.computeIfAbsent(item.submissionId(), key -> new HashMap<>())
                        .computeIfAbsent(item.step(), key -> new HashSet<>()).add(item.email()));
        Map<UUID, List<Work>> bySystem = new LinkedHashMap<>();
        for (SubmissionStore.InFlight item : inFlight) {
            Map<SubmissionStep, Set<String>> steps = acted.getOrDefault(item.id(), Map.of());
            bySystem.computeIfAbsent(item.systemId(), key -> new ArrayList<>()).add(new Work(item.id(), item.cycleYear(),
                    item.state(), readiness.filesScanning(item.id()), steps.getOrDefault(SubmissionStep.SUBMIT, Set.of()),
                    steps.getOrDefault(SubmissionStep.REVIEW, Set.of()),
                    steps.getOrDefault(SubmissionStep.ENDORSE, Set.of())));
        }
        return bySystem;
    }
}
