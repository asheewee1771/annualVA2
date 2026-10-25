package sg.annualva.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.submission.SubmissionStep;
import sg.annualva.submission.WaitingWork;
import sg.annualva.submission.WaitingWork.Waiting;

/**
 * The deadline reminders (stories 88, 89 and 90, ADR 0051). Each pass ({@code DeadlineReminderJob}: 08:00 in Singapore,
 * then hourly to the end of the day) reads where every piece of waiting work stands ({@link WaitingWork}) and reminds the
 * person whose step it is, by the one table below ({@link #schedule}):
 *
 * <ul>
 *   <li>submitting (the SMO/Tech Lead) and reviewing (the Cyber Manager): 5 days before the window their step waits on
 *       ends and on its last day, then every 7 days after the day the Overdue sweep flagged it while the flag stays set,
 *       each weekly reminder with a copy, a message of its own since the relay has no cc, to the next person up: the
 *       Cyber Manager for submitting, each VA POC for reviewing;
 *   <li>endorsing (both VA POCs, as a pair that either one's endorsement or return clears): 2 weeks and 1 week before
 *       the Quarter's last day.
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

    /** Who is told: a route of the {@link Notifications} port. */
    private interface Route extends BiConsumer<Notifications, Notice> {}

    /** The weekly reminder while a step is Overdue, and its copy to the next person up. */
    private record Weekly(NoticeAction action, NoticeAction copy, Route copyTo, Function<Waiting, String> copyLink) {}

    /**
     * How a step is reminded: its person, the link that opens their work, the reminder for each number of days left
     * before the window ends, and the weekly one while it is Overdue (none for endorsing: story 90 asks for none, and the
     * sweep's Overdue message tells both VA POCs).
     */
    private record Schedule(Route to, Function<Waiting, String> link, Map<Long, NoticeAction> daysBefore,
            @Nullable Weekly weekly) {}

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

    /** The reminder schedule of each step, written once. */
    private static Schedule schedule(SubmissionStep step) {
        return switch (step) {
            case SUBMIT -> new Schedule(Notifications::toSmo,
                    waiting -> Links.forSmo(waiting.state(), waiting.submissionId()),
                    Map.of(5L, NoticeAction.SUBMIT_DUE_SOON, 0L, NoticeAction.SUBMIT_DUE_TODAY),
                    new Weekly(NoticeAction.SUBMIT_STILL_OVERDUE, NoticeAction.SUBMIT_OVERDUE_COPY,
                            Notifications::toCyberManager, waiting -> Links.CYBER_MANAGER_INBOX));
            case REVIEW -> new Schedule(Notifications::toCyberManager, waiting -> Links.review(waiting.started()),
                    Map.of(5L, NoticeAction.REVIEW_DUE_SOON, 0L, NoticeAction.REVIEW_DUE_TODAY),
                    new Weekly(NoticeAction.REVIEW_STILL_OVERDUE, NoticeAction.REVIEW_OVERDUE_COPY,
                            Notifications::toEachVaPoc, waiting -> Links.endorse(waiting.started())));
            case ENDORSE -> new Schedule((port, notice) -> port.toVaPocs(notice, Awaits.ENDORSEMENT),
                    waiting -> Links.endorse(waiting.started()),
                    Map.of(14L, NoticeAction.ENDORSE_DUE_IN_TWO_WEEKS, 7L, NoticeAction.ENDORSE_DUE_IN_ONE_WEEK), null);
        };
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

    private void remind(Waiting waiting, LocalDate today, Instant now) {
        Schedule schedule = schedule(waiting.step());
        NoticeAction before = schedule.daysBefore().get(ChronoUnit.DAYS.between(today, waiting.dueOn()));
        if (before != null) {
            schedule.to().accept(notifications, notice(waiting, before, schedule.link(), today, now));
        }
        Weekly weekly = schedule.weekly();
        if (weekly != null && weeklyDue(waiting.overdueOn(), today)) {
            schedule.to().accept(notifications, notice(waiting, weekly.action(), schedule.link(), today, now));
            weekly.copyTo().accept(notifications, notice(waiting, weekly.copy(), weekly.copyLink(), today, now));
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

    private static Notice notice(Waiting waiting, NoticeAction action, Function<Waiting, String> link, LocalDate today,
            Instant now) {
        return Notice.reminder(waiting.systemId(), waiting.submissionId(), waiting.state().name(), action,
                waiting.dueOn(), link.apply(waiting), now, today);
    }
}
