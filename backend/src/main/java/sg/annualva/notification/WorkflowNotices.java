package sg.annualva.notification;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import sg.annualva.access.RoleHolderChanged;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.register.SystemChangeEvent;
import sg.annualva.submission.CycleClosed;
import sg.annualva.submission.ExemptionEvent;
import sg.annualva.submission.OverdueEvent;
import sg.annualva.submission.SubmissionEvent;
import sg.annualva.submission.SubmissionState;
import sg.annualva.submission.SubmissionStep;
import sg.annualva.submission.Submissions;

/**
 * Turns what the workflow did into notifications for whoever acts next (story 92 and the Notifications section of the
 * spec), once it has committed: each listener runs after the publishing transaction commits, in a transaction of its
 * own, so a refused or rolled-back command tells nobody, and a notification that cannot be written leaves the move it
 * reports as it is (Spring logs the failure). Who is told at a step, and where their link points, is the port's one rule
 * ({@link Notifications#toStep}); what they are told at each step is one table ({@link StepNotices}). Who is told:
 *
 * <ul>
 *   <li>submitted for review: the System's Cyber Manager;
 *   <li>Returned for Rework by the Cyber Manager: the SMO/Tech Lead, whose action names what is Disputed; by a VA POC
 *       (ticket 35): the SMO/Tech Lead, pointed at the VA POC's comment, and the Cyber Manager, whom the resubmission
 *       comes back to;
 *   <li>passed to endorsement: both VA POCs, as a pair; a VA POC's endorsement or return clears it for both;
 *   <li>Overdue: the person whose step it is (the SMO/Tech Lead while it is Not Started, a Draft or Returned for Rework,
 *       the Cyber Manager In Review, both VA POCs Awaiting Endorsement);
 *   <li>moved to another Quarter or transferred by the Register: the SMO/Tech Lead, and on a transfer the former one too;
 *   <li>Withdrawn because the Register retired the System (ticket 43): the SMO/Tech Lead it last named; a withdrawal from
 *       Awaiting Endorsement clears the VA POC pair, as an endorsement would;
 *   <li>made Not Endorsed by its VA Cycle's close: the SMO/Tech Lead; a VA POC's close also clears the endorsement pair of
 *       a VA Submission that was Awaiting Endorsement, as an endorsement would (ticket 41, ADR 0044), and the pairs of one
 *       that was Exemption Requested;
 *   <li>an Exemption requested: both VA POCs, as a pair; a request from Awaiting Endorsement leaves the endorsement pair
 *       open. Decided: the request's pair clears for both, an approval clears the endorsement pair too, and the SMO/Tech
 *       Lead is told (ticket 40, ADR 0048);
 *   <li>a conditional Exemption's VA recorded: both VA POCs, as a pair; confirmed: the pair clears and the SMO/Tech Lead
 *       is told; lapsed: the SMO/Tech Lead, and whoever's step the restored state waits on (ticket 42, ADR 0051);
 *   <li>any of these to an SMO/Tech Lead or Cyber Manager whose role for the System is suspended or lapsed: both VA POCs
 *       instead, as a pair that clears once a VA POC restores the role or a Register names someone else (decided
 *       2026-10-26; {@link Notifications#toSmo}).
 * </ul>
 */
@Component
class WorkflowNotices {

    private final Notifications notifications;
    private final RegisterSystems register;
    private final Submissions submissions;
    private final CycleCalendar calendar;

    WorkflowNotices(Notifications notifications, RegisterSystems register, Submissions submissions,
            CycleCalendar calendar) {
        this.notifications = notifications;
        this.register = register;
        this.submissions = submissions;
        this.calendar = calendar;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(SubmissionEvent event) {
        UUID id = event.submissionId();
        handledBy(event.from(), event.to()).forEach(awaits -> notifications.handled(id, awaits, event.actorId(),
                event.at()));
        Notice.About moved = new Notice.About(event.systemId(), id, event.to(), event.dueOn(), event.at());
        if (event.to() == SubmissionState.WITHDRAWN) {
            // The Register retired the System (ticket 43), whatever state the VA Submission was in, so its SMO/Tech Lead
            // lost it: their inbox is the link, as on a transfer.
            notifications.toSmo(moved.tell(NoticeAction.WITHDRAWN, Links.SMO_INBOX));
            return;
        }
        if (event.from() == SubmissionState.EXEMPTION_REQUESTED) {
            notifications.toStep(SubmissionStep.SUBMIT, moved, event.to() == SubmissionState.EXEMPTED
                    ? NoticeAction.EXEMPTION_APPROVED : NoticeAction.EXEMPTION_REJECTED);
            return;
        }
        switch (event.to()) {
            case IN_REVIEW, AWAITING_ENDORSEMENT -> arrived(moved);
            case RETURNED_FOR_REWORK -> returned(event.from(), moved);
            case EXEMPTION_REQUESTED -> notifications.toVaPocs(
                    moved.tell(NoticeAction.DECIDE_EXEMPTION, Links.EXEMPTION_QUEUE), Awaits.EXEMPTION);
            // Endorsed is an FYI for the weekly digest (story 93, Release 2); the other states arrive with their tickets.
            default -> { }
        }
    }

    /**
     * Tells whoever acts next on the notice's state that the work is now theirs, after a step of someone else, by the
     * table ({@link StepNotices#onArrival}): the Cyber Manager to review, both VA POCs to endorse. The submitting step has
     * no arrival notice, and a state that waits on no step tells nobody.
     */
    private void arrived(Notice.About about) {
        about.state().nextStep().ifPresent(step -> StepNotices.of(step).onArrival()
                .ifPresent(action -> notifications.toStep(step, about, action)));
    }

    /**
     * The VA POC pairs a move clears for both VA POCs (story 69), the one rule every listener asks: leaving Awaiting
     * Endorsement handles the endorsement, except for an Exemption request, which freezes it and whose rejection returns
     * it there; leaving Exemption Requested (a decision, or the Cycle's close) handles the request, and the endorsement
     * too unless the move returns it to Awaiting Endorsement. Clearing a pair that is not open changes nothing.
     */
    static Set<Awaits> handledBy(SubmissionState from, SubmissionState to) {
        return switch (from) {
            case AWAITING_ENDORSEMENT -> to == SubmissionState.EXEMPTION_REQUESTED ? Set.of() : Set.of(Awaits.ENDORSEMENT);
            case EXEMPTION_REQUESTED -> to == SubmissionState.AWAITING_ENDORSEMENT ? Set.of(Awaits.EXEMPTION)
                    : Set.of(Awaits.EXEMPTION, Awaits.ENDORSEMENT);
            default -> Set.of();
        };
    }

    /**
     * A conditional Exemption (ticket 42): a recorded VA asks both VA POCs, as a pair, to confirm it; a confirmation clears
     * the pair and tells the SMO/Tech Lead. A lapse tells the SMO/Tech Lead, and whoever's step the restored state waits
     * on as if it had just moved there: the Cyber Manager In Review, both VA POCs Awaiting Endorsement.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(ExemptionEvent event) {
        UUID id = event.submissionId();
        Notice.About about = new Notice.About(event.systemId(), id, event.state(), event.dueOn(), event.at());
        switch (event.kind()) {
            case VA_RECORDED -> notifications.toVaPocs(about.tell(NoticeAction.CONFIRM_VA, Links.EXEMPTION_QUEUE),
                    Awaits.VA_CONFIRMATION);
            case VA_CONFIRMED -> {
                notifications.handled(id, Awaits.VA_CONFIRMATION, Objects.requireNonNull(event.actorId(),
                        "a confirmation has its VA POC"), event.at());
                notifications.toStep(SubmissionStep.SUBMIT, about, NoticeAction.VA_CONFIRMED);
            }
            case LAPSED -> lapsed(about);
        }
    }

    /**
     * A lapse tells the SMO/Tech Lead, then whoever the restored state's work arrives at: the Cyber Manager In Review,
     * both VA POCs Awaiting Endorsement.
     */
    private void lapsed(Notice.About lapse) {
        notifications.toStep(SubmissionStep.SUBMIT, lapse, lapse.state() == SubmissionState.NOT_ENDORSED
                ? NoticeAction.EXEMPTION_LAPSED_NOT_ENDORSED : NoticeAction.EXEMPTION_LAPSED);
        arrived(lapse);
    }

    /**
     * Overdue tells whoever's step the flagged state waits on (the Overdue rule, V20, flags no final or frozen state): the
     * SMO/Tech Lead for a System Not Started (with no VA Submission or one a rejected Exemption request left), a Draft or
     * one Returned for Rework, the Cyber Manager In Review, both VA POCs Awaiting Endorsement.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(OverdueEvent event) {
        Notice.About flagged = new Notice.About(event.systemId(), event.submissionId(), event.state(), event.dueOn(),
                event.at());
        event.state().nextStep().ifPresent(step -> notifications.toStep(step, flagged, StepNotices.of(step).overdue()));
    }

    /**
     * One notice per VA Submission the close made Not Endorsed, to its SMO/Tech Lead, linked to the VA Submission (read
     * only now). The lifecycle job's close acts for nobody, so it handles no endorsement pair: a pair can be marked handled
     * only by a VA POC.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(CycleClosed event) {
        for (CycleClosed.NotEndorsed item : event.notEndorsed()) {
            UUID closedBy = event.closedBy();
            SubmissionState from = item.from();
            if (closedBy != null && from != null) {
                handledBy(from, SubmissionState.NOT_ENDORSED).forEach(awaits -> notifications.handled(
                        item.submissionId(), awaits, closedBy, event.at()));
            }
            notifications.toStep(SubmissionStep.SUBMIT, new Notice.About(item.systemId(), item.submissionId(),
                    SubmissionState.NOT_ENDORSED, null, event.at()), NoticeAction.NOT_ENDORSED);
        }
    }

    /**
     * A VA POC gave a System someone who can act at a step again (restored the role, or applied a Register that revoked
     * it or brought a lapsed one back): the pair that told both VA POCs nobody could act there clears for both.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(RoleHolderChanged event) {
        notifications.roleHolderChanged(event.systemId(), event.role(), event.by(), event.at());
    }

    /**
     * The state and due date are the System's in the VA Cycle running today, as a VA POC reads them (the apply's caller):
     * Not Started, due by its new Quarter's Submission Window, when it has no VA Submission in that Cycle. The SMO/Tech
     * Lead is linked where they take it up in that state; a former one, their inbox.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(SystemChangeEvent event) {
        register.named(event.systemId()).ifPresent(system -> {
            Notice.About standing = standing(system, event);
            switch (event.change()) {
                case MOVED -> notifications.toStep(SubmissionStep.SUBMIT, standing, NoticeAction.SYSTEM_MOVED);
                case TRANSFERRED -> {
                    notifications.toStep(SubmissionStep.SUBMIT, standing, NoticeAction.SYSTEM_TRANSFERRED_TO_YOU);
                    notifications.to(Objects.requireNonNull(event.formerSmoEmail(), "a transfer names its former SMO"),
                            standing.tell(NoticeAction.SYSTEM_TRANSFERRED_FROM_YOU, Links.SMO_INBOX).leaving());
                }
            }
        });
    }

    /**
     * Where the System stands in the VA Cycle running today, as the apply's caller (a VA POC, who sees every System) reads
     * it: its VA Submission's state and due date, or Not Started and due by its Quarter's Submission Window.
     */
    private Notice.About standing(RegisterSystems.Named system, SystemChangeEvent event) {
        VaCycle cycle = calendar.running(null);
        Submissions.Summary started = submissions.inCycle(List.of(system.id()), cycle.year()).get(system.id());
        if (started == null) {
            Quarter quarter = cycle.quarters().get(system.quarter() - 1);
            return new Notice.About(system.id(), null, SubmissionState.NOT_STARTED, submissions.notStartedDueOn(quarter),
                    event.at());
        }
        return new Notice.About(system.id(), started.id(), started.state(), started.dueOn(), event.at());
    }

    /**
     * A return for rework, linked to the rework queue: the Cyber Manager's (from In Review) tells the SMO/Tech Lead to
     * rework what is Disputed; a VA POC's (from Awaiting Endorsement, ticket 35) tells the SMO/Tech Lead to read the VA
     * POC's comment, and gives the Cyber Manager a heads-up, at the review, that the resubmission comes back to them.
     */
    private void returned(SubmissionState from, Notice.About returned) {
        if (from == SubmissionState.AWAITING_ENDORSEMENT) {
            notifications.toStep(SubmissionStep.SUBMIT, returned, NoticeAction.VA_POC_REWORK);
            notifications.toStep(SubmissionStep.REVIEW, returned, NoticeAction.VA_POC_RETURNED);
        } else {
            notifications.toStep(SubmissionStep.SUBMIT, returned, NoticeAction.REWORK);
        }
    }
}
