package sg.annualva.finding;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One review by a Cyber Manager (story 50). The comment and the justification are what a person wrote: data, rendered only
 * as text, never logged or audited.
 *
 * @param id the review's own id
 * @param decision confirmed or Disputed
 * @param comment what the Cyber Manager wrote; always present on a Dispute, null on a confirmation without one
 * @param proposalId for a Finding, the proposal reviewed: the review holds for the Finding only while this is its current
 *     proposal; null for a not-applicable justification
 * @param justification for a not-applicable Scan Report, the justification as it was when reviewed (it is overwritten when
 *     the SMO/Tech Lead rewrites it, so this keeps the reviewed wording, decision 2026-10-24); null for a Finding
 * @param justificationVersion for a not-applicable Scan Report, the version of the justification reviewed: the review is of
 *     the current justification only while this is the Scan Report's {@code justificationVersion}; null for a Finding
 * @param reviewedBy who reviewed (an id, never a name or email)
 * @param reviewedAt when (UTC)
 */
public record ReviewView(
        UUID id, ReviewDecision decision, @Nullable String comment, @Nullable UUID proposalId,
        @Nullable String justification, @Nullable Integer justificationVersion, UUID reviewedBy, Instant reviewedAt) {}
