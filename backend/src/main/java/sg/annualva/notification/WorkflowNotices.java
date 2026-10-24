package sg.annualva.notification;

import java.util.List;
import java.util.Objects;
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
import sg.annualva.submission.OverdueEvent;
import sg.annualva.submission.SubmissionEvent;
import sg.annualva.submission.SubmissionState;
import sg.annualva.submission.Submissions;

/**
 * Turns what the workflow did into notifications for whoever acts next (story 92 and the Notifications section of the
 * spec), once it has committed: each listener runs after the publishing transaction commits, in a transaction of its
 * own, so a refused or rolled-back command tells nobody, and a notification that cannot be written leaves the move it
 * reports as it is (Spring logs the failure). Who is told:
 *
 * <ul>
 *   <li>submitted for review: the System's Cyber Manager;
 *   <li>Returned for Rework (by the Cyber Manager, or a VA POC with ticket 35): the SMO/Tech Lead, whose action names what
 *       is Disputed;
 *   <li>passed to endorsement: both VA POCs, as a pair; a VA POC's endorsement or return clears it for both;
 *   <li>Overdue: the person whose step it is (the SMO/Tech Lead while it is Not Started, a Draft or Returned for Rework,
 *       the Cyber Manager In Review, both VA POCs Awaiting Endorsement);
 *   <li>moved to another Quarter or transferred by the Register: the SMO/Tech Lead, and on a transfer the former one too.
 * </ul>
 */
@Component
class WorkflowNotices {

    /** The state code of a System with no VA Submission in the Cycle. */
    static final String NOT_STARTED = "NOT_STARTED";

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
        if (event.from() == SubmissionState.AWAITING_ENDORSEMENT) {
            notifications.handled(id, Awaits.ENDORSEMENT, event.actorId(), event.at());
        }
        switch (event.to()) {
            case IN_REVIEW -> notifications.toCyberManager(notice(event, NoticeAction.REVIEW, Links.review(id)));
            case RETURNED_FOR_REWORK -> notifications.toSmo(notice(event, NoticeAction.REWORK, Links.rework(id)));
            case AWAITING_ENDORSEMENT -> notifications.toVaPocs(notice(event, NoticeAction.ENDORSE, Links.endorse(id)),
                    Awaits.ENDORSEMENT);
            // Endorsed is an FYI for the weekly digest (story 93, Release 2); the other states arrive with their tickets.
            default -> { }
        }
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(OverdueEvent event) {
        if (NOT_STARTED.equals(event.state())) {
            notifications.toSmo(overdue(event, NoticeAction.SUBMIT_OVERDUE, Links.SMO_INBOX));
            return;
        }
        UUID id = Objects.requireNonNull(event.submissionId(), "a flagged VA Submission has an id");
        switch (SubmissionState.valueOf(event.state())) {
            case DRAFT -> notifications.toSmo(overdue(event, NoticeAction.SUBMIT_OVERDUE, Links.prepare(id)));
            case RETURNED_FOR_REWORK -> notifications.toSmo(overdue(event, NoticeAction.SUBMIT_OVERDUE, Links.rework(id)));
            case IN_REVIEW -> notifications.toCyberManager(overdue(event, NoticeAction.REVIEW_OVERDUE, Links.review(id)));
            case AWAITING_ENDORSEMENT -> notifications.toVaPocs(
                    overdue(event, NoticeAction.ENDORSE_OVERDUE, Links.endorse(id)), Awaits.ENDORSEMENT);
            // The Overdue rule (V20) flags no other state: a final or frozen one waits on no window.
            default -> { }
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
     * it: its VA Submission's state and due date, or Not Started and due by its Quarter's Submission Window.
     */
    private Notice standing(RegisterSystems.Named system, SystemChangeEvent event) {
        VaCycle cycle = calendar.running(null);
        Submissions.Summary started = submissions.inCycle(List.of(system.id()), cycle.year()).get(system.id());
        if (started == null) {
            Quarter quarter = cycle.quarters().get(system.quarter() - 1);
            return new Notice(system.id(), null, NOT_STARTED, NoticeAction.SYSTEM_MOVED,
                    submissions.notStartedDueOn(quarter), Links.SMO_INBOX, event.at());
        }
        return new Notice(system.id(), started.id(), started.state().name(), NoticeAction.SYSTEM_MOVED, started.dueOn(),
                Links.prepare(started.id()), event.at());
    }

    private static Notice notice(SubmissionEvent event, NoticeAction action, String link) {
        return new Notice(event.systemId(), event.submissionId(), event.to().name(), action, event.dueOn(), link,
                event.at());
    }

    private static Notice overdue(OverdueEvent event, NoticeAction action, String link) {
        return new Notice(event.systemId(), event.submissionId(), event.state(), action, event.dueOn(), link,
                event.at());
    }
}
