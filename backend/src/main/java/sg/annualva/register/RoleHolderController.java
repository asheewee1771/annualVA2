package sg.annualva.register;

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

    /**
     * What a completed review says it checked: the grants the screen listed; none means every grant listed now.
     *
     * @param grantIds the grants' ids; null or absent for every grant listed now
     */
    public record CompleteRequest(@Nullable List<UUID> grantIds) {}

    /** Records that the caller has completed a review of the role holders the body names (ADR 0056 item 4). */
    @PostMapping("/api/role-holders/reviews")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<CompletedReview> complete(@RequestBody(required = false) @Nullable CompleteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reviews.complete(body == null ? null : body.grantIds()));
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
