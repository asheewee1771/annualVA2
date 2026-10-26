package sg.annualva.notification;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.requiredInstant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.submission.SubmissionState;

/**
 * The notification table (V27). Constant SQL, named parameters (as-2). A notification names its System by id only: its
 * System ID and name are read through the Register's own module ({@link sg.annualva.register.RegisterSystems#named}).
 */
@Repository
class NotificationStore {

    /**
     * A notification as kept, before its System is named.
     *
     * @param handledBy the name of the VA POC who handled a pair (their email when the identity provider gave no name)
     */
    record Row(UUID id, UUID systemId, @Nullable UUID submissionId, SubmissionState state, NoticeAction action,
            @Nullable LocalDate dueOn, String link, Instant createdAt, @Nullable Instant readAt,
            @Nullable String handledBy, @Nullable Instant handledAt) {}

    private static final String INSERT =
            "INSERT INTO notification (recipient_id, system_id, submission_id, state, action, due_on, link, created_at, "
            + "group_key, awaits, reminder_on) VALUES (:recipient, :system, :submission, :state, :action, :dueOn, :link, "
            + ":at, :groupKey, :awaits, :reminderOn) ON CONFLICT (recipient_id, action, system_id, due_on, reminder_on) "
            + "WHERE reminder_on IS NOT NULL DO NOTHING RETURNING id";

    /** Every open pair a VA POC's decision on the VA Submission handles: both rows of each, with who and when. */
    private static final String HANDLE =
            "UPDATE notification SET handled_at = :at, handled_by = :handler WHERE submission_id = :submission "
            + "AND awaits = :awaits AND group_key IS NOT NULL AND handled_at IS NULL";

    /** Every open pair about a System and a step nobody could act at, once a VA POC gave it someone again. */
    private static final String HANDLE_ON_SYSTEM =
            "UPDATE notification SET handled_at = :at, handled_by = :handler WHERE system_id = :system "
            + "AND action = :action AND awaits = :awaits AND group_key IS NOT NULL AND handled_at IS NULL";

    /** A notification as its reader lists it, with the name of the VA POC who handled a pair. */
    private static final String COLUMNS =
            "SELECT n.id, n.system_id, n.submission_id, n.state, n.action, n.due_on, n.link, n.created_at, n.read_at, "
            + "n.handled_at, coalesce(h.display_name, h.email::text) AS handled_by "
            + "FROM notification n LEFT JOIN app_user h ON h.id = n.handled_by "
            + "WHERE n.recipient_id = :recipient ";

    private static final String PAGE = COLUMNS + "ORDER BY n.created_at DESC, n.id LIMIT :limit OFFSET :offset";

    private static final String ONE = COLUMNS + "AND n.id = :id";

    private static final String COUNT = "SELECT count(*) FROM notification WHERE recipient_id = :recipient";

    /** The first read is kept: marking a read notification read again changes nothing. */
    private static final String MARK_READ =
            "UPDATE notification SET read_at = coalesce(read_at, :at) WHERE id = :id AND recipient_id = :recipient";

    private final JdbcClient jdbc;

    NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One person's row of a notice; {@code groupKey} and {@code awaits} are set together, for a VA POC pair only. A
     * reminder this person already has for the same action, System, due date and day is not written again (ADR 0052).
     *
     * @return the notification's id; nothing when the reminder was written before
     */
    Optional<UUID> insert(UUID recipient, Notice notice, @Nullable UUID groupKey, @Nullable Awaits awaits) {
        return jdbc.sql(INSERT)
                .param("recipient", recipient)
                .param("system", notice.systemId())
                .param("submission", notice.submissionId())
                .param("state", notice.state().name())
                .param("action", notice.action().name())
                .param("dueOn", notice.dueOn())
                .param("link", notice.link())
                .param("at", utc(notice.at()))
                .param("groupKey", groupKey)
                .param("awaits", awaits == null ? null : awaits.name())
                .param("reminderOn", notice.reminderOn())
                .query(UUID.class)
                .optional();
    }

    void handle(UUID submissionId, Awaits awaits, UUID handler, Instant at) {
        jdbc.sql(HANDLE).param("submission", submissionId).param("awaits", awaits.name()).param("handler", handler)
                .param("at", utc(at)).update();
    }

    void handleOnSystem(UUID systemId, NoticeAction action, UUID handler, Instant at) {
        jdbc.sql(HANDLE_ON_SYSTEM).param("system", systemId).param("action", action.name())
                .param("awaits", Awaits.ROLE_HOLDER.name()).param("handler", handler).param("at", utc(at)).update();
    }

    List<Row> page(UUID recipient, int page, int size) {
        return jdbc.sql(PAGE).param("recipient", recipient).param("limit", size).param("offset", (long) page * size)
                .query((rs, row) -> row(rs)).list();
    }

    long count(UUID recipient) {
        return jdbc.sql(COUNT).param("recipient", recipient).query(Long.class).single();
    }

    Optional<Row> find(UUID id, UUID recipient) {
        return jdbc.sql(ONE).param("id", id).param("recipient", recipient).query((rs, row) -> row(rs)).optional();
    }

    /** Whether the notification is the recipient's; only then is it marked read. */
    boolean markRead(UUID id, UUID recipient, Instant at) {
        return jdbc.sql(MARK_READ).param("id", id).param("recipient", recipient).param("at", utc(at)).update() == 1;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getObject("submission_id", UUID.class), SubmissionState.valueOf(rs.getString("state")),
                NoticeAction.valueOf(rs.getString("action")), rs.getObject("due_on", LocalDate.class),
                rs.getString("link"), requiredInstant(rs, "created_at"),
                instant(rs, "read_at"), rs.getString("handled_by"), instant(rs, "handled_at"));
    }

}
