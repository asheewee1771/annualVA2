package sg.annualva.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.submission.SubmissionState;

/**
 * What a notification says, before it is addressed: the System, the state, the action, the due date and a link, and
 * nothing else (story 91). There is no field for free text, so no Finding, vulnerability or comment can reach a
 * notification. A sender describes what happened ({@link About}) and says what it tells and where it links with
 * {@link About#tell}; a notice to whoever acts at a step gets both from the port ({@link Notifications#toStep}).
 *
 * @param submissionId the VA Submission it is about; null for a Not Started System
 * @param state {@code NOT_STARTED} for a System with no VA Submission, or the VA Submission's state
 * @param dueOn the last day of the window the state waits on; null when none applies
 * @param link the app path that opens the item ({@link Links})
 * @param at when it happened (the clock bean's instant of the move it reports)
 * @param reminderOn for a deadline reminder (ADR 0052), the business day it is for: a person gets at most one
 *     notification with the same action, System, due date and day, however often the planner runs; null for a notice of
 *     a move, which is told once because the move happens once
 */
public record Notice(UUID systemId, @Nullable UUID submissionId, SubmissionState state, NoticeAction action,
        @Nullable LocalDate dueOn, String link, Instant at, @Nullable LocalDate reminderOn) {

    /**
     * What a notice is about, before anyone is told anything: the System, its VA Submission and state, the due date, when
     * it happened and, for a reminder, its business day.
     */
    public record About(UUID systemId, @Nullable UUID submissionId, SubmissionState state, @Nullable LocalDate dueOn,
            Instant at, @Nullable LocalDate reminderOn) {

        /** A move, an Overdue flag or a Register change: told once. */
        public About(UUID systemId, @Nullable UUID submissionId, SubmissionState state, @Nullable LocalDate dueOn,
                Instant at) {
            this(systemId, submissionId, state, dueOn, at, null);
        }

        /** The notice that tells this action, linked here. */
        public Notice tell(NoticeAction action, String link) {
            return new Notice(systemId, submissionId, state, action, dueOn, link, at, reminderOn);
        }
    }

    /**
     * The same notice for someone who no longer acts on the System (a former SMO/Tech Lead): no due date, and their inbox
     * as the link, since what it pointed at is no longer theirs.
     */
    public Notice leaving() {
        return new Notice(systemId, submissionId, state, action, null, Links.SMO_INBOX, at, reminderOn);
    }
}
