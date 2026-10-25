package sg.annualva.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The mail outbox (V28). Constant SQL, named parameters (as-2). Each statement stands alone, so the dispatcher holds no
 * row lock while it calls the relay (ADR 0012): a claim takes the next due row with {@code SKIP LOCKED}, counts the
 * attempt and leases the row, and an outcome is written only while the row is still the claim it read (same attempt,
 * still pending). A claim takes only rows whose template and action this build knows, so an instance running an older
 * build leaves a newer kind of mail to the instances that can send it.
 */
@Repository
class MailOutboxStore {

    private static final String QUEUE =
            "INSERT INTO mail_outbox (notification_id, template, created_at, next_attempt_at) "
            + "VALUES (:notification, :template, :at, :at)";

    /** The next due row, claimed and leased, with what its notification says and to whom. */
    private static final String CLAIM =
            "WITH due AS (SELECT o.id FROM mail_outbox o JOIN notification n ON n.id = o.notification_id "
            + "WHERE o.status = 'PENDING' AND o.next_attempt_at <= :now AND o.attempts < :max "
            + "AND o.template IN (:templates) AND n.action IN (:actions) "
            + "ORDER BY o.next_attempt_at, o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED), "
            + "claimed AS (UPDATE mail_outbox o SET attempts = o.attempts + 1, next_attempt_at = :leaseUntil, "
            + "last_attempt_at = :now FROM due WHERE o.id = due.id "
            + "RETURNING o.id, o.notification_id, o.template, o.attempts) "
            + "SELECT c.id, c.template, c.attempts, u.email::text AS recipient, n.system_id, n.state, n.action, "
            + "n.due_on, n.link FROM claimed c "
            + "JOIN notification n ON n.id = c.notification_id JOIN app_user u ON u.id = n.recipient_id";

    /** The claims whose dispatcher stopped after their last attempt: nothing will try them again. */
    private static final String ABANDON =
            "UPDATE mail_outbox SET status = 'FAILED' WHERE status = 'PENDING' AND attempts >= :max "
            + "AND next_attempt_at <= :now";

    private static final String CLAIM_HELD = " WHERE id = :id AND attempts = :attempts AND status = 'PENDING'";

    private static final String SENT =
            "UPDATE mail_outbox SET status = 'SENT', sent_at = :at, relay_message_id = :messageId" + CLAIM_HELD;

    private static final String SETTLE = "UPDATE mail_outbox SET status = :status" + CLAIM_HELD;

    private static final String RETRY = "UPDATE mail_outbox SET next_attempt_at = :at" + CLAIM_HELD;

    private static final List<String> TEMPLATES = Arrays.stream(MailTemplate.values()).map(Enum::name).toList();
    private static final List<String> ACTIONS = Arrays.stream(NoticeAction.values()).map(Enum::name).toList();

    /**
     * A claimed mail and what it says: the notification's recipient, System, state, action, due date and link. The
     * System is an id: its System ID and name are read through the Register's module when the mail is sent.
     *
     * @param attempts the attempt this claim is, counting it
     * @param recipient the address; never logged
     */
    record Claimed(UUID id, MailTemplate template, int attempts, String recipient, UUID systemId,
            String state, NoticeAction action, @Nullable LocalDate dueOn, String link) {

        /** Ids and counts only: the address stays out of any log line a framework writes about it (lm-19). */
        @Override
        public String toString() {
            return "Claimed[id=" + id + ", template=" + template + ", attempts=" + attempts + "]";
        }
    }

    /** How a claimed mail settled for good. */
    enum Settled {
        /** The relay refused the address. */
        REJECTED,
        /** A value fell outside the allow-list; the relay was never called. */
        REFUSED,
        /** Every attempt failed. */
        FAILED
    }

    private final JdbcClient jdbc;

    MailOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void queue(UUID notificationId, MailTemplate template, Instant at) {
        jdbc.sql(QUEUE).param("notification", notificationId).param("template", template.name()).param("at", utc(at))
                .update();
    }

    /** Settles as failed every claim that was a last attempt and whose lease has run out; returns how many. */
    int abandonExpired(Instant now, int max) {
        return jdbc.sql(ABANDON).param("max", max).param("now", utc(now)).update();
    }

    /** Claims the next due mail, if any, and leases it until {@code leaseUntil}. */
    Optional<Claimed> claimNext(Instant now, Instant leaseUntil, int max) {
        return jdbc.sql(CLAIM).param("now", utc(now)).param("leaseUntil", utc(leaseUntil)).param("max", max)
                .param("templates", TEMPLATES).param("actions", ACTIONS)
                .query((rs, row) -> new Claimed(rs.getObject("id", UUID.class),
                        MailTemplate.valueOf(rs.getString("template")), rs.getInt("attempts"),
                        rs.getString("recipient"), rs.getObject("system_id", UUID.class), rs.getString("state"), NoticeAction.valueOf(rs.getString("action")),
                        rs.getObject("due_on", LocalDate.class), rs.getString("link")))
                .optional();
    }

    /** @return whether the claim was still held, so the outcome was written */
    boolean sent(Claimed mail, String messageId, Instant at) {
        return held(jdbc.sql(SENT).param("messageId", messageId).param("at", utc(at)), mail).update() == 1;
    }

    /** @return whether the claim was still held, so the outcome was written */
    boolean settle(Claimed mail, Settled status) {
        return held(jdbc.sql(SETTLE).param("status", status.name()), mail).update() == 1;
    }

    /** @return whether the claim was still held, so the next attempt was set */
    boolean retryAt(Claimed mail, Instant at) {
        return held(jdbc.sql(RETRY).param("at", utc(at)), mail).update() == 1;
    }

    private static JdbcClient.StatementSpec held(JdbcClient.StatementSpec statement, Claimed mail) {
        return statement.param("id", mail.id()).param("attempts", mail.attempts());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
