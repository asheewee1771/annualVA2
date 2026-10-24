package sg.annualva.submission;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A VA Submission moved from one state to another. It is published inside the command's transaction; a consumer (the
 * notifications, ADR 0043) listens after the commit, so a command that was refused or rolled back is never heard of. It
 * carries ids, states and a date only, never report content.
 *
 * @param actorId the person whose command moved it
 * @param at when it moved (the clock bean's instant)
 * @param dueOn the last day of the window the new state waits on, by the one Overdue rule (V20); null for a final or
 *     frozen state
 */
public record SubmissionEvent(
        UUID submissionId, UUID systemId, SubmissionState from, SubmissionState to, UUID actorId, Instant at,
        @Nullable LocalDate dueOn) {}
