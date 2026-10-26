package sg.annualva.submission;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The Overdue sweep flagged a VA Submission, or a System with none in a VA Cycle (Not Started). Published inside the
 * sweep's transaction; the notifications listen after the commit (ADR 0043). It carries ids, a fixed state code and a
 * date only.
 *
 * @param submissionId the VA Submission flagged; null for a Not Started System
 * @param state the state it was flagged in ({@code NOT_STARTED} for a Not Started System)
 * @param dueOn the last day of the window that passed (the one rule, V20)
 * @param at when the sweep flagged it (the clock bean's instant)
 */
public record OverdueEvent(@Nullable UUID submissionId, UUID systemId, SubmissionState state, @Nullable LocalDate dueOn,
        Instant at) {}
