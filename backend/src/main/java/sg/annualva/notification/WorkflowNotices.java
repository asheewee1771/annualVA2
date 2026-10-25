package sg.annualva.notification;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
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
 * reports as it is (Spring logs the failure). Who is told:
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
 *       is told; lapsed: the SMO/Tech Lead, and whoever's step the restored state waits on (ticket 42, ADR 0051).
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
        // Linked where the SMO/Tech Lead takes it up in the new state; each case below sets the action it tells.
        Notice moved = new Notice(event.systemId(), id, event.to(), NoticeAction.WITHDRAWN, event.dueOn(),
                Links.forSmo(event.to(), id), event.at());
        if (event.to() == SubmissionState.WITHDRAWN) {
            // The Register retired the System (ticket 43), whatever state the VA Submission was in, so its SMO/Tech Lead
            // lost it: their inbox is the link, as on a transfer.
            notifications.toSmo(moved.linked(NoticeAction.WITHDRAWN, Links.SMO_INBOX));
            return;
        }
        if (event.from() == SubmissionState.EXEMPTION_REQUESTED) {
            notifications.toSmo(moved.as(event.to() == SubmissionState.EXEMPTED ? NoticeAction.EXEMPTION_APPROVED
                    : NoticeAction.EXEMPTION_REJECTED));
            return;
        }
        switch (event.to()) {
            case IN_REVIEW, AWAITING_ENDORSEMENT -> arrived(moved);
            case RETURNED_FOR_REWORK -> returned(event.from(), moved);
            case EXEMPTION_REQUESTED -> notifications.toVaPocs(
                    moved.linked(NoticeAction.DECIDE_EXEMPTION, Links.EXEMPTION_QUEUE), Awaits.EXEMPTION);
            // Endorsed is an FYI for the weekly digest (story 93, Release 2); the other states arrive with their tickets.
            default -> { }
        }
    }

    /**
     * Tells whoever acts at this step about the notice's VA Submission, with this action, at the link that opens their
     * work there: the one recipient rule ({@link Notifications#toStep}) and the one link rule ({@link Links#forStep}).
     */
    private void toStep(SubmissionStep step, Notice about, NoticeAction action) {
        notifications.toStep(step, about.linked(action, Links.forStep(step, about.state(), about.submissionId())));
    }

    /**
     * Tells the person whose step the notice's state now waits on that it is theirs, after a step of someone else: the
     * Cyber Manager to review it In Review, both VA POCs to endorse it Awaiting Endorsement. A state the SMO/Tech Lead
     * acts on is told in words of its own (a return, a lapse), and one that waits on nobody tells nobody.
     */
    private void arrived(Notice about) {
        about.state().nextStep().filter(step -> step != SubmissionStep.SUBMIT).ifPresent(step -> toStep(step, about,
                step == SubmissionStep.REVIEW ? NoticeAction.REVIEW : NoticeAction.ENDORSE));
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
        Notice notice = new Notice(event.systemId(), id, event.state(), NoticeAction.CONFIRM_VA, event.dueOn(),
                Links.EXEMPTION_QUEUE, event.at());
        switch (event.kind()) {
            case VA_RECORDED -> notifications.toVaPocs(notice, Awaits.VA_CONFIRMATION);
            case VA_CONFIRMED -> {
                notifications.handled(id, Awaits.VA_CONFIRMATION, Objects.requireNonNull(event.actorId(),
                        "a confirmation has its VA POC"), event.at());
                notifications.toSmo(notice.linked(NoticeAction.VA_CONFIRMED, Links.forSmo(event.state(), id)));
            }
            case LAPSED -> lapsed(notice.linked(NoticeAction.EXEMPTION_LAPSED, Links.forSmo(event.state(), id)));
        }
    }

    /**
     * A lapse tells the SMO/Tech Lead, and whoever's step the restored state waits on as if it had just moved there,
     * unless that is the SMO/Tech Lead already told.
     */
    private void lapsed(Notice lapse) {
        notifications.toSmo(lapse.state() == SubmissionState.NOT_ENDORSED
                ? lapse.as(NoticeAction.EXEMPTION_LAPSED_NOT_ENDORSED) : lapse);
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
        Notice flagged = new Notice(event.systemId(), event.submissionId(), event.state(), NoticeAction.SUBMIT_OVERDUE,
                event.dueOn(), Links.forSmo(event.state(), event.submissionId()), event.at());
        event.state().nextStep().ifPresent(step -> toStep(step, flagged, switch (step) {
            case SUBMIT -> NoticeAction.SUBMIT_OVERDUE;
            case REVIEW -> NoticeAction.REVIEW_OVERDUE;
            case ENDORSE -> NoticeAction.ENDORSE_OVERDUE;
        }));
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
            notifications.toSmo(new Notice(item.systemId(), item.submissionId(), SubmissionState.NOT_ENDORSED,
                    NoticeAction.NOT_ENDORSED, null, Links.forSmo(SubmissionState.NOT_ENDORSED, item.submissionId()),
                    event.at()));
        }
    }

    /**
     * The state and due date are the System's in the VA Cycle running today, as a VA POC reads them (the apply's caller):
     * Not Started, due by its new Quarter's Submission Window, when it has no VA Submission in that Cycle.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(SystemChangeEvent event) {
        register.named(event.systemId()).ifPresent(system -> {
            Notice standing = standing(system, event);
            switch (event.change()) {
                case MOVED -> notifications.toSmo(standing);
                case TRANSFERRED -> {
                    notifications.toSmo(standing.as(NoticeAction.SYSTEM_TRANSFERRED_TO_YOU));
                    notifications.to(Objects.requireNonNull(event.formerSmoEmail(), "a transfer names its former SMO"),
                            standing.as(NoticeAction.SYSTEM_TRANSFERRED_FROM_YOU).leaving());
                }
            }
        });
    }

    /**
     * Where the System stands in the VA Cycle running today, as the apply's caller (a VA POC, who sees every System) reads
     * it: its VA Submission's state and due date, or Not Started and due by its Quarter's Submission Window, linked where
     * the SMO/Tech Lead takes it up in that state ({@link Links#forSmo}).
     */
    private Notice standing(RegisterSystems.Named system, SystemChangeEvent event) {
        VaCycle cycle = calendar.running(null);
        Submissions.Summary started = submissions.inCycle(List.of(system.id()), cycle.year()).get(system.id());
        if (started == null) {
            Quarter quarter = cycle.quarters().get(system.quarter() - 1);
            return new Notice(system.id(), null, SubmissionState.NOT_STARTED, NoticeAction.SYSTEM_MOVED,
                    submissions.notStartedDueOn(quarter), Links.forSmo(SubmissionState.NOT_STARTED, null), event.at());
        }
        return new Notice(system.id(), started.id(), started.state(), NoticeAction.SYSTEM_MOVED, started.dueOn(),
                Links.forSmo(started.state(), started.id()), event.at());
    }

    /**
     * A return for rework, linked to the rework queue: the Cyber Manager's (from In Review) tells the SMO/Tech Lead to
     * rework what is Disputed; a VA POC's (from Awaiting Endorsement, ticket 35) tells the SMO/Tech Lead to read the VA
     * POC's comment, and the Cyber Manager that the resubmission comes back to them.
     */
    private void returned(SubmissionState from, Notice returned) {
        if (from == SubmissionState.AWAITING_ENDORSEMENT) {
            notifications.toSmo(returned.as(NoticeAction.VA_POC_REWORK));
            toStep(SubmissionStep.REVIEW, returned, NoticeAction.VA_POC_RETURNED);
        } else {
            notifications.toSmo(returned.as(NoticeAction.REWORK));
        }
    }
}
