package sg.annualva.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link MailOutbox#dispatch} on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012). Seam 1 tests
 * leave it off, move the clock and run the dispatcher themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class MailDispatchJob {

    private final MailOutbox outbox;

    MailDispatchJob(MailOutbox outbox) {
        this.outbox = outbox;
    }

    /** Between passes rather than at a fixed rate, so a slow relay never stacks passes up. */
    @Scheduled(fixedDelayString = "${annualva.mail.dispatch-interval:30s}")
    void run() {
        outbox.dispatch();
    }
}
