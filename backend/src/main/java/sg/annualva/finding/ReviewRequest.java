package sg.annualva.finding;

import org.jspecify.annotations.Nullable;

/**
 * The body of a review, every field as text so that a field that cannot be read is named in the answer.
 *
 * @param decision CONFIRMED or DISPUTED
 * @param comment why: required to Dispute, optional to confirm
 * @param proposalId for a Finding, the id of the proposal reviewed, which must still be its current one; not used for a
 *     not-applicable justification
 * @param justificationVersion for a not-applicable justification, the version of it the screen showed (a whole number,
 *     the Scan Report's {@code justificationVersion}), which must still be the current one; not used for a Finding
 */
public record ReviewRequest(
        @Nullable String decision, @Nullable String comment, @Nullable String proposalId,
        @Nullable String justificationVersion) {}
