package sg.annualva.submission;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exemptions (ADR 0046). The SMO/Tech Lead of a System requests one (another role is refused with 403, an SMO of another
 * System gets 404, ADR 0013); a VA POC approves or rejects it after a fresh sign-in.
 */
@RestController
public class ExemptionController {

    private final Exemptions exemptions;

    ExemptionController(Exemptions exemptions) {
        this.exemptions = exemptions;
    }

    /**
     * Requests an Exemption and freezes the System's VA Submission, creating it for a Not Started System: 201 with the
     * request and its address.
     *
     * @param id the System's own id (not its business System ID)
     */
    @PostMapping("/api/systems/{id}/exemptions")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<Exemptions.Exemption> request(@PathVariable UUID id, @RequestBody Exemptions.Request request) {
        Exemptions.Exemption requested = exemptions.request(id, request);
        return ResponseEntity.created(URI.create("/api/exemptions/" + requested.id()))
                .cacheControl(CacheControl.noStore())
                .body(requested);
    }

    /** Approves a pending Exemption: 200 with it decided. A step-up action ({@code exemption-decide}). */
    @PostMapping("/api/exemptions/{id}/approve")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<Exemptions.Exemption> approve(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exemptions.approve(id));
    }

    /** Rejects a pending Exemption: 200 with it decided. A step-up action ({@code exemption-decide}). */
    @PostMapping("/api/exemptions/{id}/reject")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<Exemptions.Exemption> reject(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exemptions.reject(id));
    }
}
