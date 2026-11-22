package sg.annualva.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link DeadlineReminders} when {@code annualva.scheduling.enabled} is true (ADR 0012, ADR 0052): at 08:00 business
 * time and then on every hour to the end of the day ({@code annualva.reminders.cron}). The first pass of the day sends its
 * reminders; the later ones write only what it could not, such as a reminder whose write failed or a morning the
 * application was down, since a person never gets the same reminder twice in a day. The next day's passes remind by
 * their own day only, so a reminder a whole day missed is not sent late.
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class DeadlineReminderJob {

    private final DeadlineReminders reminders;
    private final KeySignals signals;

    DeadlineReminderJob(DeadlineReminders reminders, KeySignals signals) {
        this.reminders = reminders;
        this.signals = signals;
    }

    /**
     * Recorded as completed (ticket 67) unless every reminder that was due failed ({@code reminder-failed}); a pass with no
     * reminder due, or one where only some failed, has completed.
     */
    @Scheduled(cron = "${annualva.reminders.cron:0 0 8-23 * * *}", zone = "${annualva.time-zone}")
    void run() {
        if (reminders.plan()) {
            signals.jobCompleted(Job.DEADLINE_REMINDERS);
        }
    }
}
