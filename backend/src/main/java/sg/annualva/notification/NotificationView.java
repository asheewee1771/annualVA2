package sg.annualva.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.submission.SubmissionState;

/**
 * A notification as its reader lists it: the System, the state, the action, the due date and a link (story 91), when it
 * came and was read, and for an item both VA POCs received, who handled it (story 69). Every value is an id, a fixed code,
 * fixed text or a date, apart from the System's ID and name and the handler's name, which the screen renders as text.
 *
 * @param systemCode the System ID as the Register holds it
 * @param submissionId null for a Not Started System
 * @param action the fixed code of {@link NoticeAction}
 * @param message the app's fixed sentence for the action
 * @param dueOn a business date (Asia/Singapore); null when none applies
 * @param readAt when the reader first marked it read (UTC); null while unread
 * @param handledBy the name of the VA POC who made the decision that cleared it; null until one did
 * @param handledAt when they did (UTC)
 */
public record NotificationView(
        UUID id, UUID systemId, String systemCode, String systemName, @Nullable UUID submissionId, SubmissionState state,
        String action, String message, @Nullable LocalDate dueOn, String link, Instant createdAt,
        @Nullable Instant readAt, @Nullable String handledBy, @Nullable Instant handledAt) {}
