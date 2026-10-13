package sg.annualva.finding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.Page;

/**
 * The Findings read from a VA Submission's Scan Reports (story 28), for anyone who may see its System: its SMO/Tech Lead,
 * its Cyber Manager and the VA POCs. One the caller may not see is 404, the same as one that does not exist (ADR 0013).
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
     * the files it was read from and how many places it was reported. The read is audited, with the count and no content.
     */
    @GetMapping("/api/submissions/{id}/findings")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Page<FindingView>> findings(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(findings.list(id, page, size));
    }
}
