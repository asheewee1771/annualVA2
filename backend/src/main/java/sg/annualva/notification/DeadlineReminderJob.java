package sg.annualva.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link DeadlineReminders} when {@code annualva.scheduling.enabled} is true (ADR 0012, ADR 0052): at 08:00 business
 * time and then on every hour to the end of the day ({@code annualva.reminders.cron}). The first pass of the day sends its
 * reminders; the later ones write only what it could not, such as a reminder whose write failed or a morning the
 * application was down, since a person never gets the same reminder twice in a day. The next day's passes remind by
 * their own day only, so a reminder a whole day missed is not sent late. Seam 1 tests leave it off, move the clock and
 * run the planner themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class DeadlineReminderJob {

    private final DeadlineReminders reminders;

    DeadlineReminderJob(DeadlineReminders reminders) {
        this.reminders = reminders;
    }

    @Scheduled(cron = "${annualva.reminders.cron:0 0 8-23 * * *}", zone = "${annualva.time-zone}")
    void run() {
        reminders.plan();
    }
}
