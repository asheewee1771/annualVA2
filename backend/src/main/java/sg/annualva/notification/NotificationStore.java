package sg.annualva.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The notification table (V136). Constant SQL, named parameters (as-2). */
@Repository
class NotificationStore {

    private static final String INSERT =
            "INSERT INTO notification (recipient_id, system_id, submission_id, state, action, due_on, link, created_at, "
            + "group_key, awaits, reminder_on) VALUES (:recipient, :system, :submission, :state, :action, :dueOn, :link, "
            + ":at, :groupKey, :awaits, :reminderOn) ON CONFLICT (recipient_id, action, system_id, due_on, reminder_on) "
            + "WHERE reminder_on IS NOT NULL DO NOTHING RETURNING id";

    /** Every open pair a VA POC's decision on the VA Submission handles: both rows of each, with who and when. */
    private static final String HANDLE =
            "UPDATE notification SET handled_at = :at, handled_by = :handler WHERE submission_id = :submission "
            + "AND awaits = :awaits AND group_key IS NOT NULL AND handled_at IS NULL";

    /**
     * A notification as its reader lists it: the System's ID and name as the Register holds them, and the name of the
     * VA POC who handled a pair (their email when the identity provider gave no name).
     */
    private static final String COLUMNS =
            "SELECT n.id, n.system_id, s.code::text AS system_code, s.name AS system_name, n.submission_id, n.state, "
            + "n.action, n.due_on, n.link, n.created_at, n.read_at, n.handled_at, "
            + "coalesce(h.display_name, h.email::text) AS handled_by "
            + "FROM notification n JOIN va_system s ON s.id = n.system_id LEFT JOIN app_user h ON h.id = n.handled_by "
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
                .param("state", notice.state())
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

    List<NotificationView> page(UUID recipient, int page, int size) {
        return jdbc.sql(PAGE).param("recipient", recipient).param("limit", size).param("offset", (long) page * size)
                .query((rs, row) -> view(rs)).list();
    }

    long count(UUID recipient) {
        return jdbc.sql(COUNT).param("recipient", recipient).query(Long.class).single();
    }

    Optional<NotificationView> find(UUID id, UUID recipient) {
        return jdbc.sql(ONE).param("id", id).param("recipient", recipient).query((rs, row) -> view(rs)).optional();
    }

    /** Whether the notification is the recipient's; only then is it marked read. */
    boolean markRead(UUID id, UUID recipient, Instant at) {
        return jdbc.sql(MARK_READ).param("id", id).param("recipient", recipient).param("at", utc(at)).update() == 1;
    }

    private static NotificationView view(ResultSet rs) throws SQLException {
        NoticeAction action = NoticeAction.valueOf(rs.getString("action"));
        return new NotificationView(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getString("system_code"), rs.getString("system_name"), rs.getObject("submission_id", UUID.class),
                rs.getString("state"), action.name(), action.message(), rs.getObject("due_on", LocalDate.class),
                rs.getString("link"), instant(rs, "created_at"), instant(rs, "read_at"), rs.getString("handled_by"),
                instant(rs, "handled_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
