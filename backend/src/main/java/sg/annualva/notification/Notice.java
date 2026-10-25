package sg.annualva.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a notification says, before it is addressed: the System, the state, the action, the due date and a link, and
 * nothing else (story 91). There is no field for free text, so no Finding, vulnerability or comment can reach a
 * notification.
 *
 * @param submissionId the VA Submission it is about; null for a Not Started System
 * @param state the fixed state code: {@code NOT_STARTED} or a VA Submission state
 * @param dueOn the last day of the window the state waits on; null when none applies
 * @param link the app path that opens the item ({@link Links})
 * @param at when it happened (the clock bean's instant of the move it reports)
 * @param reminderOn for a deadline reminder (ADR 0052), the business day it is for: a person gets at most one
 *     notification with the same action, System, due date and day, however often the planner runs; null for a notice of
 *     a move, which is told once because the move happens once
 */
public record Notice(UUID systemId, @Nullable UUID submissionId, String state, NoticeAction action,
        @Nullable LocalDate dueOn, String link, Instant at, @Nullable LocalDate reminderOn) {

    /** A notice of a move. */
    public Notice(UUID systemId, @Nullable UUID submissionId, String state, NoticeAction action,
            @Nullable LocalDate dueOn, String link, Instant at) {
        this(systemId, submissionId, state, action, dueOn, link, at, null);
    }

    /** A deadline reminder for a business day: it always has the due date it reminds of. */
    static Notice reminder(UUID systemId, @Nullable UUID submissionId, String state, NoticeAction action,
            LocalDate dueOn, String link, Instant at, LocalDate day) {
        return new Notice(systemId, submissionId, state, action, dueOn, link, at, day);
    }

    /** The same notice with another action. */
    public Notice as(NoticeAction other) {
        return new Notice(systemId, submissionId, state, other, dueOn, link, at, reminderOn);
    }

    /** The same notice with another action and link. */
    public Notice linked(NoticeAction other, String otherLink) {
        return new Notice(systemId, submissionId, state, other, dueOn, otherLink, at, reminderOn);
    }

    /**
     * The same notice for someone who no longer acts on the System (a former SMO/Tech Lead): no due date, and their inbox
     * as the link, since what it pointed at is no longer theirs.
     */
    public Notice leaving() {
        return new Notice(systemId, submissionId, state, action, null, Links.SMO_INBOX, at, reminderOn);
    }
}
