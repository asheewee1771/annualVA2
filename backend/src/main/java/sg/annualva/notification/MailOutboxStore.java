package sg.annualva.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The mail outbox (V137). Constant SQL, named parameters (as-2). Each statement stands alone, so the dispatcher holds no
 * row lock while it calls the relay (ADR 0012): a claim takes due rows with {@code SKIP LOCKED}, counts the attempt and
 * leases the row, and an outcome is written only while the row is still the claim it read (same attempt, still pending).
 */
@Repository
class MailOutboxStore {

    private static final String QUEUE =
            "INSERT INTO mail_outbox (notification_id, template, created_at, next_attempt_at) "
            + "VALUES (:notification, :template, :at, :at)";

    /** The due rows, claimed and leased, with what their notifications say and to whom. */
    private static final String CLAIM =
            "WITH due AS (SELECT id FROM mail_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "AND attempts < :max ORDER BY next_attempt_at, id LIMIT :batch FOR UPDATE SKIP LOCKED), "
            + "claimed AS (UPDATE mail_outbox o SET attempts = o.attempts + 1, next_attempt_at = :leaseUntil, "
            + "last_attempt_at = :now FROM due WHERE o.id = due.id "
            + "RETURNING o.id, o.notification_id, o.template, o.attempts) "
            + "SELECT c.id, c.template, c.attempts, u.email::text AS recipient, s.code::text AS system_code, "
            + "s.name AS system_name, n.state, n.action, n.due_on, n.link FROM claimed c "
            + "JOIN notification n ON n.id = c.notification_id JOIN app_user u ON u.id = n.recipient_id "
            + "JOIN va_system s ON s.id = n.system_id";

    /** A claim whose dispatcher stopped after its last attempt: nothing will try it again. */
    private static final String ABANDON =
            "UPDATE mail_outbox SET status = 'FAILED' WHERE status = 'PENDING' AND attempts >= :max "
            + "AND next_attempt_at <= :now";

    private static final String CLAIM_HELD = " WHERE id = :id AND attempts = :attempts AND status = 'PENDING'";

    private static final String SENT =
            "UPDATE mail_outbox SET status = 'SENT', sent_at = :at, relay_message_id = :messageId" + CLAIM_HELD;

    private static final String SETTLE = "UPDATE mail_outbox SET status = :status" + CLAIM_HELD;

    private static final String RETRY = "UPDATE mail_outbox SET next_attempt_at = :at" + CLAIM_HELD;

    /**
     * A claimed mail and what it says: the notification's recipient, System, state, action, due date and link.
     *
     * @param attempts the attempt this claim is, counting it
     * @param recipient the address; never logged
     */
    record Claimed(UUID id, MailTemplate template, int attempts, String recipient, String systemCode, String systemName,
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

    List<Claimed> claim(Instant now, Instant leaseUntil, int batch, int max) {
        jdbc.sql(ABANDON).param("max", max).param("now", utc(now)).update();
        return jdbc.sql(CLAIM).param("now", utc(now)).param("leaseUntil", utc(leaseUntil)).param("batch", batch)
                .param("max", max)
                .query((rs, row) -> new Claimed(rs.getObject("id", UUID.class),
                        MailTemplate.valueOf(rs.getString("template")), rs.getInt("attempts"),
                        rs.getString("recipient"), rs.getString("system_code"), rs.getString("system_name"),
                        rs.getString("state"), NoticeAction.valueOf(rs.getString("action")),
                        rs.getObject("due_on", LocalDate.class), rs.getString("link")))
                .list();
    }

    void sent(Claimed mail, String messageId, Instant at) {
        held(jdbc.sql(SENT).param("messageId", messageId).param("at", utc(at)), mail).update();
    }

    void settle(Claimed mail, Settled status) {
        held(jdbc.sql(SETTLE).param("status", status.name()), mail).update();
    }

    void retryAt(Claimed mail, Instant at) {
        held(jdbc.sql(RETRY).param("at", utc(at)), mail).update();
    }

    private static JdbcClient.StatementSpec held(JdbcClient.StatementSpec statement, Claimed mail) {
        return statement.param("id", mail.id()).param("attempts", mail.attempts());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
