package sg.annualva.submission;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How far the Cyber Manager's review of a VA Submission is, for the commands that move it out of In Review (stories 55 and
 * 57). The Findings and the not-applicable justifications are kept by the module above this one, so it implements this,
 * and the return and the pass to endorsement read it under their exclusive lock on the VA Submission, which every review
 * waits for (ADR 0035 item 3), without reaching up into it. Ids, fixed codes and counts only, never report content.
 */
public interface ReviewProgress {

    /**
     * Every reason the VA Submission cannot pass to endorsement now, in a fixed order: Findings awaiting review, Findings
     * Disputed, then each not-applicable justification awaiting review or Disputed. Empty when every Finding and every
     * justification is confirmed. Each reason is a {@link SubmitReadiness.Blocker} naming no file. Must run in the caller's
     * transaction.
     */
    List<SubmitReadiness.Blocker> unconfirmed(UUID submissionId);

    /**
     * What is Disputed now, each named by the review that Disputed it: the Findings in the order they were read, then the
     * not-applicable justifications. Empty when nothing is. Must run in the caller's transaction.
     */
    List<Disputed> disputed(UUID submissionId);

    /**
     * One Disputed item: a Finding ({@code findingId}) or the justification of a Scan Report marked not applicable
     * ({@code reportType}), never both, with the review that Disputed it.
     */
    record Disputed(@Nullable UUID findingId, @Nullable String reportType, UUID reviewId) {}
}
