package sg.annualva.finding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
 * its SMO or an Auditor is refused with 403, and an SMO of another System gets 404. The same SMO/Tech Lead applies one
 * status to several Findings in one batch (story 37), and anyone who may see the System reads the progress counts (story
 * 38). The same SMO/Tech Lead types in the Findings of a Scan Report its tool cannot export (story 23).
 */
@RestController
public class FindingController {

    static final int DEFAULT_PAGE_SIZE = 200;
    static final int MAX_PAGE_SIZE = 500;

    private final Findings findings;
    private final BulkProposals bulk;
    private final ManualFindings manual;
    private final Reviews reviews;

    FindingController(Findings findings, BulkProposals bulk, ManualFindings manual, Reviews reviews) {
        this.findings = findings;
        this.bulk = bulk;
        this.manual = manual;
        this.reviews = reviews;
    }

    /**
     * The Cyber Manager confirms or Disputes the Finding's current proposal (story 50): 201 with the review. The body names
     * the {@code decision}, a {@code comment} (required to Dispute) and the {@code proposalId} reviewed, which must still be
     * the current one (else 409 {@code proposal-changed}). Someone who acted at another step of the VA Submission, or is
     * assigned to one, is refused with 403 {@code sod-conflict}; a VA Submission that is not In Review is 409
     * {@code invalid-transition}; an SMO/Tech Lead, a VA POC or an Auditor is refused with 403 by the role rule.
     */
    @PostMapping("/api/findings/{id}/reviews")
    @PreAuthorize("hasRole('CYBER_MANAGER')")
    public ResponseEntity<ReviewView> review(@PathVariable UUID id, @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reviews.reviewFinding(id, request));
    }

    /**
     * The Cyber Manager confirms or Disputes a Bulk-set batch as a whole (story 51): 201 with the batch review. The body
     * names the {@code decision} and a {@code comment} (required to Dispute), which every member decided carries. It
     * decides every member whose current proposal is the batch's and awaits review, and names the others, which changed
     * since, in {@code leftOut}; none awaiting is 409 {@code nothing-to-review}. Segregation of duties, the state and the
     * roles are checked as for one Finding.
     */
    @PostMapping("/api/batches/{id}/reviews")
    @PreAuthorize("hasRole('CYBER_MANAGER')")
    public ResponseEntity<BatchReviewView> reviewBatch(@PathVariable UUID id, @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reviews.reviewBatch(id, request));
    }

    /**
     * A page of the Findings, the most severe first. Each carries its Scan Report, identifier, package or rule, severity,
     * the files it was read from, how many places it was reported, its current proposal and the review state of it. The
     * filters {@code reportType}, {@code severity} (repeat it or separate the values with commas), {@code reviewState} and
     * {@code needsAttention} (no proposal yet, or a Disputed one), {@code batchId} (the members whose current proposal
     * was made in that Bulk-set batch) and {@code batched} (whether the current proposal was made in a batch at all) narrow
     * the list; the total is the number that match. The read is audited, with the count and no content.
     */
    @GetMapping("/api/submissions/{id}/findings")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Page<FindingView>> findings(@PathVariable UUID id,
            @RequestParam(required = false) @Nullable ReportType reportType,
            @RequestParam(required = false) @Nullable List<Severity> severity,
            @RequestParam(required = false) @Nullable ReviewState reviewState,
            @RequestParam(defaultValue = "false") boolean needsAttention,
            @RequestParam(required = false) @Nullable UUID batchId,
            @RequestParam(required = false) @Nullable Boolean batched,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        FindingFilter filter = new FindingFilter(reportType, severity == null ? List.of() : severity, reviewState,
                needsAttention, batchId, batched);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.list(id, filter, page, size));
    }

    /**
     * The progress counts of the VA Submission's Findings (story 38): every Finding, those with a proposed status and
     * those without one, those awaiting review, the Disputed ones, and the Carried Over and Target Date Missed ones (none before Release 2),
     * and, by name, the report files more than 90 days old at today's business date ({@code tooOld}) and those with no
     * scan date to judge ({@code undated}) (story 26). A read by anyone but the System's own SMO/Tech Lead is audited,
     * with ids only.
     */
    @GetMapping("/api/submissions/{id}/readiness")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Readiness> readiness(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.readiness(id));
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

    /**
     * Bulk-set (story 37): one Finding Status with its fields for every Finding named, as one batch: 201 with the batch id
     * and the count. The list is refused as a whole (400 {@code invalid-field} naming {@code findingIds}) when one of them
     * is not a Finding of this VA Submission; the fields are checked as for one Finding; a VA Submission that is not in
     * Draft or Returned for Rework is 409 {@code invalid-transition}.
     */
    @PostMapping("/api/submissions/{id}/findings/bulk-proposals")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<BatchView> bulkPropose(@PathVariable UUID id, @RequestBody BulkProposalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(bulk.propose(id, request));
    }

    /**
     * A Finding typed in by the SMO/Tech Lead for a Scan Report marked for Findings entered by hand (story 23): 201 with the
     * Finding, which is always shown as Manually Entered. A wrong field is a 400 {@code invalid-field} that names it; a Scan
     * Report not marked MANUAL is 409 {@code wrong-report-mode}; a Finding it already has is 409
     * {@code duplicate-finding}; a VA Submission that is not in Draft or Returned for Rework is 409
     * {@code invalid-transition}.
     */
    @PostMapping("/api/submissions/{id}/findings/manual")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<FindingView> enter(@PathVariable UUID id, @RequestBody ManualFindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(manual.enter(id, request));
    }

    /**
     * The Bulk-set batches of the VA Submission in the order they were made (story 51), each with what was proposed and
     * where its members stand: awaiting review, confirmed, Disputed, or changed since. Audited for every role.
     */
    @GetMapping("/api/submissions/{id}/batches")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<List<BatchSummary>> batches(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.batches(id));
    }

    /** Every proposal of the Finding, the latest first: the history it keeps. Audited for every role. */
    @GetMapping("/api/findings/{id}/proposals")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<List<ProposalView>> proposals(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.history(id));
    }
}
