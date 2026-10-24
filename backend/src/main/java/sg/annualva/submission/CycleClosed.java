package sg.annualva.submission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A VA Cycle closed (stories 17 and 68). It is published inside the close's transaction; a consumer (the notifications,
 * tickets 36 and 37, which tell each System's SMO/Tech Lead) listens after the commit. One event per close, naming every
 * VA Submission it made Not Endorsed. It carries ids and states only.
 *
 * @param cycle the VA Cycle's year
 * @param closedBy the VA POC who closed it early; null when the lifecycle job closed it after its close date
 * @param at when it closed (the clock bean's instant)
 */
public record CycleClosed(int cycle, @Nullable UUID closedBy, Instant at, List<NotEndorsed> notEndorsed) {

    public CycleClosed {
        notEndorsed = List.copyOf(notEndorsed);
    }

    /**
     * A VA Submission the close made Not Endorsed.
     *
     * @param from the state it was in; null for a System that had none (Not Started), whose Not Endorsed VA Submission the
     *     close created
     */
    public record NotEndorsed(UUID submissionId, UUID systemId, @Nullable SubmissionState from) {}
}
