package sg.annualva.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.boundary.MailRelay;
import sg.annualva.notification.MailOutboxStore.Claimed;
import sg.annualva.notification.MailOutboxStore.Settled;
import sg.annualva.register.RegisterSystems;
import sg.annualva.register.SmoReachability;

/**
 * Email through the mail relay (stories 91 and 94, ADR 0046). A required notification is queued in the transaction that
 * writes it ({@link Notifications}), so a mail exists exactly when its in-app notification does. The dispatcher, run by
 * {@code MailDispatchJob} (or by hand in tests), first gives up on the claims whose dispatcher stopped after their last
 * attempt, then claims due mails one at a time, each leased just before its hand-over, so a slow relay never lets a lease
 * run out on a mail still waiting its turn. For each mail it refuses it when a value breaks the allow-list
 * ({@link MailValues}), and otherwise hands the recipient, the relay's id of the template and the values to the relay.
 * The relay's message id is kept; a refused address is settled for good and flags SMO Unreachable every System naming it
 * as its SMO/Tech Lead, and a mail the relay takes clears that flag of its address, each in the transaction that writes
 * the outcome ({@link SmoReachability}, ADR 0053); a failure is tried again after a wait that doubles each time, up to
 * {@code maxAttempts} attempts in all. Log lines carry the outbox id, the attempt and fixed codes: never a recipient address or a value of the mail (lm-19).
 */
@Service
public class MailOutbox {

    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);
    private static final String ACTION = "event.action";
    private static final String MAIL_ID = "labels.mail_id";
    private static final String ATTEMPTS = "labels.attempts";
    private static final String ERROR_TYPE = "error.type";

    private final MailOutboxStore store;
    private final MailRelay relay;
    private final MailProperties properties;
    private final Clock clock;
    private final SmoReachability reachability;
    private final TransactionTemplate transaction;
    private final RegisterSystems register;

    MailOutbox(MailOutboxStore store, MailRelay relay, MailProperties properties, Clock clock,
            SmoReachability reachability, TransactionTemplate transaction, RegisterSystems register) {
        this.register = register;
        this.reachability = reachability;
        this.transaction = transaction;
        this.store = store;
        this.relay = relay;
        this.properties = properties;
        this.clock = clock;
    }

    /** Queues a notification just written for email, when its action is a required message; joins the caller's transaction. */
    void queue(UUID notificationId, NoticeAction action, Instant at) {
        MailTemplate.of(action).ifPresent(mailing -> store.queue(notificationId, mailing.template(), at));
    }

    /** One pass: gives up on the stopped last attempts, then hands up to a batch of due mails to the relay. */
    public void dispatch() {
        int abandoned = store.abandonExpired(clock.instant(), properties.maxAttempts());
        if (abandoned > 0) {
            log.atError().setMessage("Mails whose last attempt never finished were given up on")
                    .addKeyValue(ACTION, "mail-abandoned").addKeyValue("labels.count", abandoned).log();
        }
        for (int sent = 0; sent < properties.batchSize(); sent++) {
            Instant now = clock.instant();
            Optional<Claimed> next = store.claimNext(now, now.plus(properties.lease()), properties.maxAttempts());
            if (next.isEmpty()) {
                return;
            }
            try {
                send(next.get());
            } catch (RuntimeException e) {
                // The outcome could not be written: the lease runs out and a later pass claims the mail again.
                log.atError().setMessage("A mail's outcome could not be recorded").addKeyValue(ACTION, "mail-record-failed")
                        .addKeyValue(MAIL_ID, next.get().id()).addKeyValue(ERROR_TYPE, e.getClass().getName()).log();
            }
        }
    }

    private void send(Claimed mail) {
        // V27's foreign key keeps the System in the Register; were it gone, the mail is refused on its System ID.
        Optional<RegisterSystems.Named> named = register.named(mail.systemId());
        Optional<String> refusal = named.map(MailValues::refusal).orElse(Optional.of(MailValues.SYSTEM_ID));
        if (refusal.isPresent()) {
            settled(mail, Settled.REFUSED);
            log.atWarn().setMessage("A mail was not sent: a value is outside the allow-list")
                    .addKeyValue(ACTION, "mail-refused").addKeyValue(MAIL_ID, mail.id())
                    .addKeyValue("event.reason", refusal.get()).log();
            return;
        }
        RegisterSystems.Named system = named.orElseThrow();
        MailRelay.Receipt receipt;
        try {
            receipt = relay.send(mail.recipient(), properties.templateId(mail.template()),
                    MailValues.of(mail, system, properties.appUrl()));
        } catch (RuntimeException e) {
            failed(mail, e);
            return;
        }
        switch (receipt) {
            case MailRelay.Accepted accepted -> {
                Instant at = clock.instant();
                if (!recorded(() -> store.sent(mail, accepted.messageId(), at),
                        () -> reachability.accepted(mail.recipient(), at))) {
                    claimLost(mail);
                }
            }
            case MailRelay.AddressRejected rejected -> {
                Instant at = clock.instant();
                if (!recorded(() -> store.settle(mail, Settled.REJECTED),
                        () -> reachability.refused(mail.recipient(), at))) {
                    claimLost(mail);
                }
                log.atWarn().setMessage("The mail relay refused a recipient address")
                        .addKeyValue(ACTION, "mail-address-rejected").addKeyValue(MAIL_ID, mail.id()).log();
            }
        }
    }

    /** The relay could not take the mail: try again after a wait that doubles each time, or give up after the last. */
    private void failed(Claimed mail, RuntimeException e) {
        if (mail.attempts() >= properties.maxAttempts()) {
            settled(mail, Settled.FAILED);
            log.atError().setMessage("A mail was given up on after its last attempt").addKeyValue(ACTION, "mail-abandoned")
                    .addKeyValue(MAIL_ID, mail.id()).addKeyValue(ATTEMPTS, mail.attempts())
                    .addKeyValue(ERROR_TYPE, e.getClass().getName()).log();
            return;
        }
        Duration wait = properties.firstRetry().multipliedBy(1L << (mail.attempts() - 1));
        if (!store.retryAt(mail, clock.instant().plus(wait))) {
            claimLost(mail);
        }
        log.atWarn().setMessage("A mail could not be handed to the relay; it is tried again later")
                .addKeyValue(ACTION, "mail-send-failed").addKeyValue(MAIL_ID, mail.id())
                .addKeyValue(ATTEMPTS, mail.attempts()).addKeyValue(ERROR_TYPE, e.getClass().getName()).log();
    }

    /**
     * Writes a relay's answer and, when the claim was still held, what it tells of the address (SMO Unreachable, ADR 0053)
     * in one transaction, so a flag is never set or cleared for an answer that was dropped.
     *
     * @return whether the claim was still held, so the answer was written
     */
    private boolean recorded(BooleanSupplier outcome, Runnable addressTold) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!outcome.getAsBoolean()) {
                return false;
            }
            addressTold.run();
            return true;
        }));
    }

    private void settled(Claimed mail, Settled status) {
        if (!store.settle(mail, status)) {
            claimLost(mail);
        }
    }

    /**
     * The lease ran out before the outcome was written, and another pass has claimed or given up on the mail since: the
     * outcome is dropped, and the mail may go out twice (at least once, ADR 0046 item 4).
     */
    private static void claimLost(Claimed mail) {
        log.atWarn().setMessage("A mail's claim was lost before its outcome was recorded")
                .addKeyValue(ACTION, "mail-claim-lost").addKeyValue(MAIL_ID, mail.id())
                .addKeyValue(ATTEMPTS, mail.attempts()).log();
    }
}
