package sg.annualva.register;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The role-holder review (IM8 ac-4, ticket 47): the VA POCs' alone (the permissions matrix, "changing roles"). */
@RestController
public class RoleHolderController {

    private final RoleHolderReviews reviews;

    RoleHolderController(RoleHolderReviews reviews) {
        this.reviews = reviews;
    }

    /** Every role holder against the Register, the automatic returns since the last review, and whether one is due. */
    @GetMapping("/api/role-holders")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<RoleHolderReviews.View> view() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.view());
    }

    /** Records that the caller has completed a review. */
    @PostMapping("/api/role-holders/reviews")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<RoleHolderReview> complete() {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(reviews.complete());
    }

    /** Suspends a person's role for a System until a Register upload names someone else (a step-up action). */
    @PostMapping("/api/role-holders/{grantId}/suspend")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<RoleHolderReviews.Holder> suspend(@PathVariable UUID grantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.suspend(grantId));
    }

    /** Lifts a suspension (a step-up action). */
    @PostMapping("/api/role-holders/{grantId}/restore")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<RoleHolderReviews.Holder> restore(@PathVariable UUID grantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.restore(grantId));
    }
}
