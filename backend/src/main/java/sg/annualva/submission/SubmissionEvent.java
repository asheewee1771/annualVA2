package sg.annualva.submission;

import java.time.Instant;
import java.util.UUID;

/**
 * A VA Submission moved from one state to another. It is published inside the command's transaction; a consumer (the
 * notifications, tickets 36 and 37) listens after the commit, so a command that was refused or rolled back is never heard
 * of. It carries ids and states only, never report content.
 *
 * @param actorId the person whose command moved it
 * @param at when it moved (the clock bean's instant)
 */
public record SubmissionEvent(
        UUID submissionId, UUID systemId, SubmissionState from, SubmissionState to, UUID actorId, Instant at) {}
