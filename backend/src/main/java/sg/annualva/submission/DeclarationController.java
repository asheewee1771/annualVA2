package sg.annualva.submission;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The current Declaration wording, for the people who read VA Submissions: the SMO/Tech Lead accepts it when submitting,
 * and the Cyber Manager and the VA POCs read what was declared.
 */
@RestController
public class DeclarationController {

    private final Declarations declarations;

    DeclarationController(Declarations declarations) {
        this.declarations = declarations;
    }

    @GetMapping("/api/declaration-text")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Declarations.Text> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(declarations.current());
    }
}
