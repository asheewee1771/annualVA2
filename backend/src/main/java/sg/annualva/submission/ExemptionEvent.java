package sg.annualva.submission;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Something happened to a conditional Exemption (ticket 42, story 47): its VA was recorded or confirmed, or it lapsed. It
 * is published inside the command's or the lapse job's transaction; the notifications listen after the commit (ADR
 * 0043). It carries ids, a state and a date only, never what a person wrote.
 *
 * @param state the VA Submission's state now: Exempted after a record or a confirmation; after a lapse the state it is
 *     back in, or Not Endorsed once its VA Cycle has closed
 * @param dueOn the last day of the window that state waits on (the one rule, V20); null when it waits on none
 * @param actorId who recorded or confirmed it; null for a lapse, which the job does for nobody
 * @param at when (the clock bean's instant)
 */
public record ExemptionEvent(
        Kind kind, UUID exemptionId, UUID submissionId, UUID systemId, SubmissionState state, @Nullable LocalDate dueOn,
        @Nullable UUID actorId, Instant at) {

    /** What happened. */
    public enum Kind {
        /** The SMO/Tech Lead recorded that the scheduled VA happened: both VA POCs are asked to confirm it. */
        VA_RECORDED,
        /** A VA POC confirmed the recorded VA: the Exemption is no longer conditional. */
        VA_CONFIRMED,
        /** Nothing was recorded by the lapse date: back where the request froze it, or Not Endorsed. */
        LAPSED
    }
}
