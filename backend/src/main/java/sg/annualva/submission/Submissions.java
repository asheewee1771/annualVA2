package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The VA Submission (story 19). An SMO/Tech Lead starts the one for a System in a VA Cycle: it is created in Draft with
 * the Submission, Review and Endorsement due dates taken from the System's Quarter, and the caller is recorded at the
 * submitting step, the first fact segregation of duties rests on. Starting is allowed before the Quarter begins. A
 * System has one VA Submission per Cycle, so a second start is refused (409 {@code invalid-transition}), as is the start
 * of a System whose Quarter is Handled outside the app. Each command checks, in this order: ownership (the access guard,
 * so a System the caller may not reach is "not found", ADR 0013), the Cycle, the state, then writes, records the step
 * and audits, all in one transaction. A refusal is audited as well, in a transaction of its own (ADR 0014), because the
 * command's own rolls back.
 */
@Service
public class Submissions {

    /** The step a person acted at on a VA Submission, and when they first did. The person is an id, never a name or email. */
    public record Acted(SubmissionStep step, UUID userId, Instant at) {}

    /**
     * A VA Submission as anyone who may see its System reads it. Dates are business dates (Asia/Singapore).
     *
     * @param cycle the VA Cycle's year
     * @param quarter 1 to 4
     * @param overdue the Overdue flag; set by the sweep of a later ticket, so false until then
     * @param endorsedLate whether it was endorsed after the Quarter's last day
     * @param startedAt when it was started (UTC)
     * @param acted who acted at which step, in the order they first did
     */
    public record Submission(
            UUID id, UUID systemId, int cycle, int quarter, SubmissionState state, boolean overdue, boolean endorsedLate,
            LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, Instant startedAt, List<Acted> acted) {}

    /**
     * Where a Scan Report file goes or is read from.
     *
     * @param systemId the System the VA Submission belongs to
     * @param acceptsFiles whether the VA Submission is in a state that takes new files (Draft or Returned for Rework)
     */
    public record FileTarget(UUID submissionId, UUID systemId, boolean acceptsFiles) {}

    /** What the SMO inbox needs of a started VA Submission. */
    public record Summary(UUID id, SubmissionState state, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue) {}

    /**
     * The states in which Scan Report files can be uploaded: the SMO/Tech Lead is still preparing (story 20). Returned for
     * Rework has no constant yet (the return command of ticket 30 adds it), so the states are named as the table holds them.
     */
    private static final Set<String> TAKES_FILES = Set.of(SubmissionState.DRAFT.name(), "RETURNED_FOR_REWORK");

    private static final String NOT_ALLOWED_NOW = "invalid-transition";

    private final SubmissionStore store;
    private final RegisterSystems register;
    private final CycleCalendar calendar;
    private final AccessGuard guard;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;

    Submissions(SubmissionStore store, RegisterSystems register, CycleCalendar calendar, AccessGuard guard,
            CurrentUser user, AuditActor actor, AuditLog audit, Clock clock) {
        this.store = store;
        this.register = register;
        this.calendar = calendar;
        this.guard = guard;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Starts the VA Submission for a System the caller is SMO/Tech Lead of.
     *
     * @param cycleYear the VA Cycle's year; null for the one running today (before the first live Cycle starts, that
     *     Cycle)
     * @throws ResourceNotFoundException for a System the caller may not submit for, or a Cycle the app does not run
     * @throws RejectedRequestException 409 {@code invalid-transition} when the System already has a VA Submission in the
     *     Cycle, or its Quarter is Handled outside the app
     */
    @Transactional
    public Submission start(UUID systemId, @Nullable Integer cycleYear) {
        RegisterSystems.ActiveSystem system = register.active(systemId, Need.SUBMIT);
        VaCycle cycle = calendar.running(cycleYear);
        Quarter quarter = cycle.quarters().get(system.quarter() - 1);
        if (quarter.handledOutsideApp()) {
            throw refused(systemId, "quarter-handled-outside-app",
                    "This System's Quarter is handled outside the app, so no VA Submission is started for it here.");
        }
        Instant now = clock.instant();
        UUID caller = user.id();
        UUID id = store.insert(systemId, cycle.year(), quarter.number(), SubmissionState.DRAFT, quarter.submitDue(),
                quarter.reviewDue(), quarter.endorseDue(), now, caller).orElseThrow(() -> refused(systemId,
                        "already-started", "This System already has a VA Submission in this VA Cycle."));
        store.recordParticipation(id, caller, SubmissionStep.SUBMIT, now);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_STARTED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(id).with(DetailKey.STATE_TO, SubmissionState.DRAFT.name()));
        return view(id);
    }

    /**
     * A VA Submission, for anyone who may see its System: the System's SMO/Tech Lead or Cyber Manager, or a VA POC.
     * One that does not exist and one the caller may not see are both "not found" (ADR 0013), and neither is audited. A
     * read by anyone but the System's own SMO/Tech Lead (a Cyber Manager or a VA POC) is audited as
     * {@code SUBMISSION_VIEWED}, in the read's transaction, with ids only; the owner's own read writes nothing
     * (reviewer-decisions.md, ADR 0020). The transaction is read-write because the entry is a write.
     */
    @Transactional
    public Submission get(UUID id) {
        Submission submission = view(id);
        if (!guard.scope(Need.SUBMIT).contains(submission.systemId())) {
            audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_VIEWED, AuditOutcome.SUCCESS))
                    .onSystem(submission.systemId()).onSubmission(id));
        }
        return submission;
    }

    /**
     * The VA Submission a Scan Report file belongs to, for the file pipeline. Ownership comes first: one the caller has no
     * scope over for this need is "not found", the same as one that does not exist (ADR 0013).
     */
    @Transactional(readOnly = true)
    public FileTarget fileTarget(UUID id, Need need) {
        SubmissionStore.Located located = store.locate(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(located.systemId(), need);
        return new FileTarget(id, located.systemId(), TAKES_FILES.contains(located.state()));
    }

    /**
     * The System a VA Submission belongs to, for a module that keeps something of its own on the VA Submission (its
     * Findings). Ownership comes first: one the caller has no scope over for this need is "not found", the same as one that
     * does not exist (ADR 0013).
     */
    @Transactional(readOnly = true)
    public UUID systemOf(UUID id, Need need) {
        UUID systemId = store.locate(id).orElseThrow(ResourceNotFoundException::new).systemId();
        guard.requireSystem(systemId, need);
        return systemId;
    }

    private Submission view(UUID id) {
        SubmissionStore.Row row = store.find(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(row.systemId(), Need.VIEW);
        return new Submission(row.id(), row.systemId(), row.cycleYear(), row.quarter(), row.state(), row.overdue(),
                row.endorsedLate(), row.submitDue(), row.reviewDue(), row.endorseDue(), row.startedAt(),
                store.participation(id));
    }

    /**
     * The started VA Submissions among these Systems in one Cycle, by System, for a read model. Only Systems the caller
     * may see are answered; a System with no VA Submission is Not Started and is absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Summary> inCycle(Collection<UUID> systemIds, int cycleYear) {
        var scope = guard.scope(Need.VIEW);
        Map<UUID, Summary> started = new HashMap<>();
        for (SubmissionStore.Row row : store.inCycle(systemIds.stream().filter(scope::contains).toList(), cycleYear)) {
            started.put(row.systemId(),
                    new Summary(row.id(), row.state(), row.submitDue(), row.reviewDue(), row.endorseDue()));
        }
        return started;
    }

    /** Audits the refusal in a transaction of its own, then answers 409; the reason and sentence are fixed text. */
    private RejectedRequestException refused(UUID systemId, String reason, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_STARTED, AuditOutcome.REFUSED))
                .onSystem(systemId).with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW, detail);
    }
}
