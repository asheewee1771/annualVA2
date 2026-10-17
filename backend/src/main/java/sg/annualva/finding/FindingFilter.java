package sg.annualva.finding;

import java.util.List;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.Severity;

/**
 * What a Findings list is narrowed to; every part is optional and the parts that are given all apply.
 *
 * @param reportType only the Findings of this Scan Report
 * @param severities only the Findings of these severities; empty for every severity
 * @param reviewState only the Findings whose current proposal is in this review state (so none without a proposal)
 * @param needsAttention only the Findings the SMO/Tech Lead still has to act on: no proposal yet, or a Disputed one
 */
public record FindingFilter(
        @Nullable ReportType reportType, List<Severity> severities, @Nullable ReviewState reviewState,
        boolean needsAttention) {

    /** Every Finding. */
    public static FindingFilter none() {
        return new FindingFilter(null, List.of(), null, false);
    }
}
