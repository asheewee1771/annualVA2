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
 */
public record ScanReportView(
        ReportType reportType, ReportMode mode, @Nullable String justification, @Nullable ReviewState reviewState) {}
