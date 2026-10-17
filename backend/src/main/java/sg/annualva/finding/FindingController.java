package sg.annualva.finding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.parser.Severity;
import sg.annualva.web.Page;

/**
 * The Findings read from a VA Submission's Scan Reports (story 28), for anyone who may see its System: its SMO/Tech Lead,
 * its Cyber Manager and the VA POCs. One the caller may not see is 404, the same as one that does not exist (ADR 0013).
 * The SMO/Tech Lead of the System proposes a Finding Status for a Finding (story 35); a Cyber Manager, a VA POC who is not
 * its SMO or an Auditor is refused with 403, and an SMO of another System gets 404.
 */
@RestController
public class FindingController {

    static final int DEFAULT_PAGE_SIZE = 200;
    static final int MAX_PAGE_SIZE = 500;

    private final Findings findings;

    FindingController(Findings findings) {
        this.findings = findings;
    }

    /**
     * A page of the Findings, the most severe first. Each carries its Scan Report, identifier, package or rule, severity,
     * the files it was read from, how many places it was reported, its current proposal and the review state of it. The
     * filters {@code reportType}, {@code severity} (repeat it or separate the values with commas), {@code reviewState} and
     * {@code needsAttention} (no proposal yet, or a Disputed one) narrow the list; the total is the number that match. The
     * read is audited, with the count and no content.
     */
    @GetMapping("/api/submissions/{id}/findings")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Page<FindingView>> findings(@PathVariable UUID id,
            @RequestParam(required = false) @Nullable ReportType reportType,
            @RequestParam(required = false) @Nullable List<Severity> severity,
            @RequestParam(required = false) @Nullable ReviewState reviewState,
            @RequestParam(defaultValue = "false") boolean needsAttention,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        FindingFilter filter = new FindingFilter(reportType, severity == null ? List.of() : severity, reviewState,
                needsAttention);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.list(id, filter, page, size));
    }

    /**
     * Proposes a Finding Status with the field it requires: 201 with the proposal. A field that is missing, not used with
     * the status, too long or in the wrong form is a 400 {@code invalid-field} problem that names it; a VA Submission that
     * is not in Draft or Returned for Rework is 409 {@code invalid-transition}.
     */
    @PostMapping("/api/findings/{id}/proposals")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<ProposalView> propose(@PathVariable UUID id, @RequestBody ProposalRequest request) {
        ProposalView proposal = findings.propose(id, request);
        return ResponseEntity.created(URI.create("/api/findings/" + id + "/proposals"))
                .cacheControl(CacheControl.noStore())
                .body(proposal);
    }

    /** Every proposal of the Finding, the latest first: the history it keeps. Audited for every role. */
    @GetMapping("/api/findings/{id}/proposals")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<List<ProposalView>> proposals(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.history(id));
    }
}
