package sg.annualva.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs {@link MailOutbox#dispatch} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012).
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class MailDispatchJob {

    private final MailOutbox outbox;
    private final KeySignals signals;

    MailDispatchJob(MailOutbox outbox, KeySignals signals) {
        this.outbox = outbox;
        this.signals = signals;
    }

    /**
     * Between passes rather than at a fixed rate, so a slow relay never stacks passes up. A pass that returns is recorded
     * as completed (ticket 67), whether or not the relay took every mail: each mail that fails is counted on
     * {@code annualva_mail_failures_total} ({@code send_failed}, {@code refused}, {@code address_rejected}, and
     * {@code record_failed} when its outcome could not be written), so even a pass where every mail failed shows there.
     */
    @Scheduled(fixedDelayString = "${annualva.mail.dispatch-interval:30s}")
    void run() {
        outbox.dispatch();
        signals.jobCompleted(Job.MAIL_DISPATCH);
    }
}
