package sg.annualva.dashboard;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Exemption requests waiting on a VA POC (story 45). They carry the evidence an SMO/Tech Lead wrote, so every read is
 * audited and nothing is cached.
 */
@RestController
public class ExemptionQueueController {

    private final ExemptionQueue queue;

    ExemptionQueueController(ExemptionQueue queue) {
        this.queue = queue;
    }

    /** @param cycle the VA Cycle's year; omitted for the Cycle running today */
    @GetMapping("/api/dashboards/exemption-queue")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<ExemptionQueue.Queue> exemptionQueue(@RequestParam(required = false) @Nullable Integer cycle) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queue.queue(cycle));
    }
}
