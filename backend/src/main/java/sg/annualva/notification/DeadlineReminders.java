package sg.annualva.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.submission.SubmissionState;
import sg.annualva.submission.WaitingWork;

/**
 * The deadline reminders (stories 88, 89 and 90, ADR 0050). Once a day ({@code DeadlineReminderJob}, 08:00 in Singapore)
 * the planner reads where every piece of waiting work stands ({@link WaitingWork}) and reminds whoever's step it is:
 *
 * <ul>
 *   <li>the SMO/Tech Lead (Not Started, Draft, Returned for Rework) and the Cyber Manager (In Review) 5 days before and
 *       on the last day of the window their step waits on, then every 7 days after the Overdue message while the flag
 *       stays set, with a copy to the next person up as a message of its own (the relay has no cc): the Cyber Manager
 *       for the SMO/Tech Lead's, each VA POC for the Cyber Manager's;
 *   <li>both VA POCs, as a pair that their endorsement or return clears, 2 weeks and 1 week before the Quarter's last
 *       day, for a VA Submission Awaiting Endorsement.
 * </ul>
 *
 * Each reminder is written under a key of its action, System, due date and day ({@link Notice#remindingOn}), so a rerun
 * of the same day, or a second instance's run, writes and mails nothing more. Each is written in a transaction of its own,
 * so one that cannot be written is logged by ids and left for a rerun, and the rest still go out.
 */
@Service
public class DeadlineReminders {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminders.class);

    /** How many days before its window ends a step's owner is first reminded. */
    private static final int DUE_SOON_DAYS = 5;

    /** How often a step that stays Overdue is reminded. */
    private static final int WEEK = 7;

    private final WaitingWork work;
    private final Notifications notifications;
    private final CycleCalendar calendar;
    private final Clock clock;

    DeadlineReminders(WaitingWork work, Notifications notifications, CycleCalendar calendar, Clock clock) {
        this.work = work;
        this.notifications = notifications;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** One pass: every reminder due on today's business date, read once from the clock. */
    public void plan() {
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        for (WaitingWork.Waiting waiting : work.on(today)) {
            try {
                remind(waiting, today, now);
            } catch (RuntimeException e) {
                log.atError().setMessage("A reminder could not be written; a rerun writes it")
                        .addKeyValue("event.action", "reminder-failed")
                        .addKeyValue("labels.system_id", waiting.systemId())
                        .addKeyValue("error.type", e.getClass().getName()).log();
            }
        }
    }

    private void remind(WaitingWork.Waiting waiting, LocalDate today, Instant now) {
        long daysLeft = ChronoUnit.DAYS.between(today, waiting.dueOn());
        UUID id = waiting.submissionId();
        switch (waiting.state()) {
            case NOT_STARTED, DRAFT, RETURNED_FOR_REWORK -> {
                String link = id == null ? Links.SMO_INBOX : Links.forSmo(waiting.state(), id);
                NoticeAction action = stepAction(daysLeft, waiting.overdue(), NoticeAction.SUBMIT_DUE_SOON,
                        NoticeAction.SUBMIT_DUE_TODAY, NoticeAction.SUBMIT_STILL_OVERDUE);
                if (action != null) {
                    notifications.toSmo(notice(waiting, action, link, today, now));
                }
                if (action == NoticeAction.SUBMIT_STILL_OVERDUE) {
                    notifications.toCyberManager(notice(waiting, NoticeAction.SUBMIT_OVERDUE_COPY,
                            Links.CYBER_MANAGER_INBOX, today, now));
                }
            }
            case IN_REVIEW -> {
                UUID submission = Objects.requireNonNull(id, "a review has a VA Submission");
                NoticeAction action = stepAction(daysLeft, waiting.overdue(), NoticeAction.REVIEW_DUE_SOON,
                        NoticeAction.REVIEW_DUE_TODAY, NoticeAction.REVIEW_STILL_OVERDUE);
                if (action != null) {
                    notifications.toCyberManager(notice(waiting, action, Links.review(submission), today, now));
                }
                if (action == NoticeAction.REVIEW_STILL_OVERDUE) {
                    notifications.toEachVaPoc(notice(waiting, NoticeAction.REVIEW_OVERDUE_COPY,
                            Links.endorse(submission), today, now));
                }
            }
            case AWAITING_ENDORSEMENT -> {
                UUID submission = Objects.requireNonNull(id, "an endorsement has a VA Submission");
                NoticeAction action = daysLeft == 2 * WEEK ? NoticeAction.ENDORSE_DUE_IN_TWO_WEEKS
                        : daysLeft == WEEK ? NoticeAction.ENDORSE_DUE_IN_ONE_WEEK : null;
                if (action != null) {
                    notifications.toVaPocs(notice(waiting, action, Links.endorse(submission), today, now),
                            Awaits.ENDORSEMENT);
                }
            }
            // WaitingWork answers only the states that wait on a window.
            default -> { }
        }
    }

    /**
     * The reminder of a step owned by one person on a day, if any: 5 days before its window ends, on the last day, and
     * every 7 days after the day the Overdue message went (the day after the last), while the flag stays set.
     */
    private static @Nullable NoticeAction stepAction(long daysLeft, boolean overdue, NoticeAction soon,
            NoticeAction dueToday, NoticeAction stillOverdue) {
        if (daysLeft == DUE_SOON_DAYS) {
            return soon;
        }
        if (daysLeft == 0) {
            return dueToday;
        }
        long sinceOverdueMessage = -daysLeft - 1;
        return overdue && sinceOverdueMessage > 0 && sinceOverdueMessage % WEEK == 0 ? stillOverdue : null;
    }

    private static Notice notice(WaitingWork.Waiting waiting, NoticeAction action, String link, LocalDate today,
            Instant now) {
        return new Notice(waiting.systemId(), waiting.submissionId(), waiting.state().name(), action, waiting.dueOn(),
                link, now).remindingOn(today);
    }
}
