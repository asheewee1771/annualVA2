package sg.annualva.submission;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.ProblemResponses;

/**
 * The Declaration wording: the current version, for the people who read VA Submissions (the SMO/Tech Lead accepts it when
 * submitting, and the Cyber Manager and the VA POCs read what was declared), and every version, which a VA POC reads and
 * adds to (ticket 61).
 */
@RestController
public class DeclarationController {

    private static final String VERSIONS = "/api/declaration-text/versions";

    private final Declarations declarations;

    DeclarationController(Declarations declarations) {
        this.declarations = declarations;
    }

    @GetMapping("/api/declaration-text")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Declarations.Text> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(declarations.current());
    }

    /** Every version, newest first: its wording, who published it and when, whether it is current, and its uses. */
    @GetMapping(VERSIONS)
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<List<Declarations.Version>> versions() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(declarations.versions());
    }

    /**
     * Publishes the next version, current at once: 201 with it as the list shows it; 400 {@code invalid-field} for a
     * wording missing or too long. A step-up action: refused with {@code step-up-required} unless the VA POC signed in
     * again within five minutes.
     */
    @PostMapping(VERSIONS)
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<Declarations.Version> publish(@Valid @RequestBody Declarations.Publish request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(declarations.publish(request));
    }

    /**
     * The one bound of a publish's body ({@link Declarations.Publish}) refused as the rule refuses a wording too long, and
     * audited the same (IM8 as-1). Only a VA POC gets this far: {@code SecurityConfig} refuses anyone else before the body
     * is read. It is checked before the step-up, so only a body no wording needs is refused here.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> sentTooLong() {
        return ResponseEntity.badRequest().body(ProblemResponses.rejected(declarations.sentTooLong()));
    }
}
