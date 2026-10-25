package sg.annualva.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.submission.WaitingWork;
import sg.annualva.submission.WaitingWork.Waiting;

/**
 * The deadline reminders (stories 88, 89 and 90, ADR 0052). Each pass ({@code DeadlineReminderJob}: 08:00 in Singapore,
 * then hourly to the end of the day) reads where every piece of waiting work stands ({@link WaitingWork}) and reminds the
 * person whose step it is, by the one per-step table ({@link StepNotices}):
 *
 * <ul>
 *   <li>submitting (the SMO/Tech Lead) and reviewing (the Cyber Manager): 5 days before the window their step waits on
 *       ends and on its last day, then every 7 days after the day the Overdue sweep flagged it while the flag stays set,
 *       each weekly reminder with a copy, a message of its own since the relay has no cc, to the next person up: the
 *       Cyber Manager for submitting, each VA POC for reviewing;
 *   <li>endorsing (both VA POCs, as a pair that either one's endorsement or return clears): 2 weeks and 1 week before
 *       the stored {@code endorse_due}: the Quarter's last day, or the VA Cycle's close after a conditional Exemption lapsed.
 * </ul>
 *
 * A reminder is for its business day: a person gets at most one with the same action, System, due date and day, so the
 * later passes of a day, or a second instance's pass, write and mail nothing more, and they write what an earlier pass
 * of the day could not. Each piece of work is reminded in a transaction of its own that first checks it is still where it
 * was read, so a failure leaves the rest to go out, and a reminder is never about a state already left.
 */
@Service
public class DeadlineReminders {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminders.class);

    /** How often, in days, a step that stays Overdue is reminded again. */
    private static final int OVERDUE_CADENCE_DAYS = 7;

    private final WaitingWork work;
    private final Notifications notifications;
    private final CycleCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate transaction;

    DeadlineReminders(WaitingWork work, Notifications notifications, CycleCalendar calendar, Clock clock,
            TransactionTemplate transaction) {
        this.work = work;
        this.notifications = notifications;
        this.calendar = calendar;
        this.clock = clock;
        this.transaction = transaction;
    }

    /**
     * One pass: every reminder due on today's business date, read once from the clock. Logs one line with how much work
     * waited and how many pieces could not be reminded (ids, counts and an exception type only).
     */
    public void plan() {
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        int waiting = 0;
        int failed = 0;
        @Nullable String failure = null;
        for (Waiting item : work.on(today)) {
            waiting++;
            try {
                transaction.executeWithoutResult(status -> {
                    if (work.stillWaiting(item)) {
                        remind(item, today, now);
                    }
                });
            } catch (RuntimeException e) {
                failed++;
                failure = e.getClass().getName();
            }
        }
        if (failure != null) {
            log.atError().setMessage("Reminders could not be written; a later pass today writes them")
                    .addKeyValue("event.action", "reminder-failed").addKeyValue("labels.count", failed)
                    .addKeyValue("error.type", failure).log();
        }
        log.atInfo().setMessage("Deadline reminders planned").addKeyValue("event.action", "reminders-planned")
                .addKeyValue("labels.waiting", waiting).addKeyValue("labels.failed", failed).log();
    }

    /** Reminds the person whose step the work waits on, by the step's row of the one table ({@link StepNotices}). */
    private void remind(Waiting waiting, LocalDate today, Instant now) {
        StepNotices step = StepNotices.of(waiting.step());
        Notice.About about = new Notice.About(waiting.systemId(), waiting.submissionId(), waiting.state(),
                waiting.dueOn(), now, today);
        NoticeAction before = step.daysBefore().get(ChronoUnit.DAYS.between(today, waiting.dueOn()));
        if (before != null) {
            notifications.toStep(waiting.step(), about, before);
        }
        StepNotices.Weekly weekly = step.weekly();
        if (weekly != null && weeklyDue(waiting.overdueOn(), today)) {
            notifications.toStep(waiting.step(), about, weekly.action());
            weekly.copyTo().accept(notifications, about.tell(weekly.copy(), weekly.copyLink().apply(about)));
        }
    }

    /**
     * Every 7 days after the day the sweep flagged the work, while it stays flagged: the sweep's own Overdue message went
     * on that day, so a weekly reminder never repeats it.
     */
    private static boolean weeklyDue(@Nullable LocalDate overdueOn, LocalDate today) {
        if (overdueOn == null) {
            return false;
        }
        long daysOverdue = ChronoUnit.DAYS.between(overdueOn, today);
        return daysOverdue > 0 && daysOverdue % OVERDUE_CADENCE_DAYS == 0;
    }
}
