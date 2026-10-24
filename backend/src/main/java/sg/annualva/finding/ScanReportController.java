package sg.annualva.finding;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the Scan Reports of a VA Submission are provided (story 23): anyone who may see the System reads them; the SMO/Tech
 * Lead of the System sets each one's mode (a Cyber Manager, a VA POC who is not its SMO or an Auditor is refused with
 * 403, an SMO of another System gets 404, ADR 0013).
 */
@RestController
public class ScanReportController {

    private final ScanReports reports;
    private final Reviews reviews;

    ScanReportController(ScanReports reports, Reviews reviews) {
        this.reports = reports;
        this.reviews = reviews;
    }

    /**
     * The Cyber Manager confirms or Disputes the justification of a Scan Report marked not applicable (story 50): 201 with
     * the review, which keeps the justification it was about. A Scan Report not marked not applicable is 409
     * {@code not-reviewable}; the other refusals are those of a Finding's review.
     */
    @PostMapping("/api/submissions/{id}/reports/{type}/review")
    @PreAuthorize("hasRole('CYBER_MANAGER')")
    public ResponseEntity<ReviewView> review(@PathVariable UUID id, @PathVariable ReportType type,
            @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reviews.reviewReport(id, type, request));
    }

    /** The three Scan Reports with their mode, justification and the review of a not-applicable one. Audited, count only. */
    @GetMapping("/api/submissions/{id}/reports")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<List<ScanReportView>> reports(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.list(id));
    }

    /**
     * Sets how one Scan Report is provided: UPLOAD, NOT_APPLICABLE (Dependency-Check or Burp Suite, with a justification)
     * or MANUAL (with a justification); 200 with the Scan Report as it is now. A wrong field is a 400 {@code invalid-field}
     * that names it; a change of mode while the Scan Report holds a report file or a Finding is 409
     * {@code report-not-empty}; a VA Submission that is not in Draft or Returned for Rework is 409
     * {@code invalid-transition}.
     */
    @PutMapping("/api/submissions/{id}/reports/{type}/mode")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<ScanReportView> setMode(@PathVariable UUID id, @PathVariable ReportType type,
            @RequestBody ReportModeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.setMode(id, type, request));
    }
}
