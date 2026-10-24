package sg.annualva.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.annualva.boundary.MailRelay;
import sg.annualva.notification.MailOutboxStore.Claimed;
import sg.annualva.notification.MailOutboxStore.Settled;

/**
 * Email through the mail relay (stories 91 and 94, ADR 0044). A required notification is queued in the transaction that
 * writes it ({@link Notifications}), so a mail exists exactly when its in-app notification does. The dispatcher, run by
 * {@code MailDispatchJob} (or by hand in tests), claims what is due and, for each mail, builds the template's values,
 * refuses it when any value falls outside the allow-list ({@link MailValues}), and otherwise hands the recipient, the
 * relay's id of the template and the values to the relay. The relay's message id is kept; a refused address is settled
 * for good (ticket 39 flags SMO Unreachable from it); a failure is tried again after a wait that doubles each time, up to
 * {@code maxAttempts} attempts in all. Log lines carry the outbox id, the attempt and fixed codes: never a recipient
 * address or a value of the mail (lm-19).
 */
@Service
public class MailOutbox {

    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);
    private static final String ACTION = "event.action";
    private static final String MAIL_ID = "labels.mail_id";
    private static final String ATTEMPTS = "labels.attempts";

    private final MailOutboxStore store;
    private final MailRelay relay;
    private final MailProperties properties;
    private final Clock clock;

    MailOutbox(MailOutboxStore store, MailRelay relay, MailProperties properties, Clock clock) {
        this.store = store;
        this.relay = relay;
        this.properties = properties;
        this.clock = clock;
    }

    /** Queues a notification just written for email, when its action is a required message; joins the caller's transaction. */
    void queue(UUID notificationId, NoticeAction action, Instant at) {
        MailTemplate.of(action).ifPresent(template -> store.queue(notificationId, template, at));
    }

    /** One pass: claims what is due, up to a batch, and hands each to the relay. */
    public void dispatch() {
        Instant now = clock.instant();
        for (Claimed mail : store.claim(now, now.plus(properties.lease()), properties.batchSize(),
                properties.maxAttempts())) {
            try {
                send(mail);
            } catch (RuntimeException e) {
                // The outcome could not be written: the lease runs out and a later pass claims the mail again.
                log.atError().setMessage("A mail's outcome could not be recorded").addKeyValue(ACTION, "mail-record-failed")
                        .addKeyValue(MAIL_ID, mail.id()).addKeyValue("error.type", e.getClass().getName()).log();
            }
        }
    }

    private void send(Claimed mail) {
        Map<String, String> values = MailValues.of(mail, properties.appUrl());
        Optional<String> outside = MailValues.outside(mail.template(), values, properties.appUrl());
        if (outside.isPresent()) {
            store.settle(mail, Settled.REFUSED);
            log.atWarn().setMessage("A mail was not sent: a value is outside the allow-list")
                    .addKeyValue(ACTION, "mail-refused").addKeyValue(MAIL_ID, mail.id())
                    .addKeyValue("event.reason", outside.get()).log();
            return;
        }
        MailRelay.Receipt receipt;
        try {
            receipt = relay.send(mail.recipient(), properties.templateId(mail.template()), values);
        } catch (RuntimeException e) {
            failed(mail, e);
            return;
        }
        switch (receipt) {
            case MailRelay.Accepted accepted -> store.sent(mail, accepted.messageId(), clock.instant());
            case MailRelay.AddressRejected rejected -> {
                store.settle(mail, Settled.REJECTED);
                log.atWarn().setMessage("The mail relay refused a recipient address")
                        .addKeyValue(ACTION, "mail-address-rejected").addKeyValue(MAIL_ID, mail.id()).log();
            }
        }
    }

    /** The relay could not take the mail: try again after a wait that doubles each time, or give up after the last. */
    private void failed(Claimed mail, RuntimeException e) {
        if (mail.attempts() >= properties.maxAttempts()) {
            store.settle(mail, Settled.FAILED);
            log.atError().setMessage("A mail was given up on after its last attempt").addKeyValue(ACTION, "mail-abandoned")
                    .addKeyValue(MAIL_ID, mail.id()).addKeyValue(ATTEMPTS, mail.attempts())
                    .addKeyValue("error.type", e.getClass().getName()).log();
            return;
        }
        Duration wait = properties.firstRetry().multipliedBy(1L << (mail.attempts() - 1));
        store.retryAt(mail, clock.instant().plus(wait));
        log.atWarn().setMessage("A mail could not be handed to the relay; it is tried again later")
                .addKeyValue(ACTION, "mail-send-failed").addKeyValue(MAIL_ID, mail.id())
                .addKeyValue(ATTEMPTS, mail.attempts()).addKeyValue("error.type", e.getClass().getName()).log();
    }
}
