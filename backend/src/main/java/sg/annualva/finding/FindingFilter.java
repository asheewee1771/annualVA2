package sg.annualva.finding;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.Severity;

/**
 * What a Findings list is narrowed to; every part is optional and the parts that are given all apply.
 *
 * @param reportType only the Findings of this Scan Report
 * @param severities only the Findings of these severities; empty for every severity
 * @param reviewState only the Findings whose current proposal is in this review state (so none without a proposal)
 * @param needsAttention only the Findings the SMO/Tech Lead still has to act on: no proposal yet, or a Disputed one
 * @param batchId only the Findings whose current proposal was made in this Bulk-set batch: its members still in it
 * @param batched true for only the Findings whose current proposal was made in a batch, false for only the others (a
 *     proposal made alone, or none); null for both
 * @param changedSinceLastReview true for only the Findings the Cyber Manager has reviewed whose latest review is not of
 *     their current proposal (proposed again since, or left with none by a rescan), false for only the others (never
 *     reviewed, or last reviewed as they are); null for both
 */
public record FindingFilter(
        @Nullable ReportType reportType, List<Severity> severities, @Nullable ReviewState reviewState,
        boolean needsAttention, @Nullable UUID batchId, @Nullable Boolean batched,
        @Nullable Boolean changedSinceLastReview) {}
