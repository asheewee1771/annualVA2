package sg.annualva.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link DeadlineReminders} every day at 08:00 business time when {@code annualva.scheduling.enabled} is true (ADR
 * 0012, ADR 0051). A day the application is down at 08:00 sends that day's reminders when it is next run, by hand or the
 * next morning no longer. Seam 1 tests leave it off, move the clock and run the planner themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class DeadlineReminderJob {

    private final DeadlineReminders reminders;

    DeadlineReminderJob(DeadlineReminders reminders) {
        this.reminders = reminders;
    }

    @Scheduled(cron = "${annualva.reminders.cron:0 0 8 * * *}", zone = "${annualva.time-zone}")
    void run() {
        reminders.plan();
    }
}
