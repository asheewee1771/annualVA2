package sg.annualva.finding;

import org.jspecify.annotations.Nullable;

/**
 * How one Scan Report of a VA Submission is provided, as anyone who may see the System reads it (story 23).
 *
 * @param reportType which of the three Scan Reports
 * @param mode uploaded, not applicable or entered by hand
 * @param justification why it is not applicable or entered by hand, as the SMO/Tech Lead wrote it (data, shown as text,
 *     never logged or audited); null when it is uploaded
 * @param reviewState where the Cyber Manager's review of a not-applicable justification is; null for any other mode
 * @param justificationVersion the version of the justification as read now: it goes up whenever the justification or the
 *     mode changes, and a review of a not-applicable justification names it, so a screen read before a rewrite cannot
 *     confirm the rewrite (ticket 28)
 * @param latestReview for a not-applicable Scan Report, the Cyber Manager's latest review of its justification, with the
 *     wording and the version it was about (story 50, decision 2026-10-24): after a Dispute and a rewrite the review state
 *     is PENDING again and this still shows what was Disputed and why; null for any other mode, and until the first
 *     review
 */
public record ScanReportView(
        ReportType reportType, ReportMode mode, @Nullable String justification, @Nullable ReviewState reviewState,
        int justificationVersion, @Nullable ReviewView latestReview) {}
