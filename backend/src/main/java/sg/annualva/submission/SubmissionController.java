package sg.annualva.submission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * VA Submissions. An SMO/Tech Lead starts one for a System of theirs (a Cyber Manager, VA POC or Auditor is refused with
 * 403; an SMO of another System gets 404, ADR 0013). Anyone who may see the System reads it.
 */
@RestController
public class SubmissionController {

    /**
     * @param systemId the System's own id (not its business System ID)
     * @param cycle the VA Cycle's year; omitted for the Cycle running today
     */
    public record StartRequest(@NotNull UUID systemId, @Nullable Integer cycle) {}

    private final Submissions submissions;

    SubmissionController(Submissions submissions) {
        this.submissions = submissions;
    }

    /** Starts the VA Submission: 201 with the Draft and its address. */
    @PostMapping("/api/submissions")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<Submissions.Submission> start(@Valid @RequestBody StartRequest request) {
        Submissions.Submission started = submissions.start(request.systemId(), request.cycle());
        return ResponseEntity.created(URI.create("/api/submissions/" + started.id()))
                .cacheControl(CacheControl.noStore())
                .body(started);
    }

    /**
     * Submits the VA Submission for review with the Declaration ticked: 200 with it In Review, or 409 listing every reason
     * it cannot be submitted yet.
     *
     * @param request whether the Declaration was ticked and the version shown; {@code {}} ticks nothing (a 409 listing the
     *     Declaration), and a request with no body at all is a 400
     */
    @PostMapping("/api/submissions/{id}/submit")
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<Submissions.Submission> submit(@PathVariable UUID id,
            @RequestBody Submissions.DeclarationAnswer request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.submit(id, request));
    }

    /**
     * Returns the VA Submission for rework: 200 with it Returned for Rework. The Cyber Manager returns it from In Review
     * with what is Disputed attached; a VA POC returns it from Awaiting Endorsement with a comment, a step-up action
     * refused with {@code step-up-required} unless they signed in again within five minutes.
     *
     * @param request the comment: optional for the Cyber Manager ({@code {}} gives none), required for a VA POC
     */
    @PostMapping("/api/submissions/{id}/return")
    @PreAuthorize("hasAnyRole('CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Submissions.Submission> returnForRework(@PathVariable UUID id,
            @RequestBody Submissions.ReturnRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.returnForRework(id, request));
    }

    /** Passes the VA Submission to endorsement: 200 with it Awaiting Endorsement, or 409 listing what is unconfirmed. */
    @PostMapping("/api/submissions/{id}/pass-to-endorsement")
    @PreAuthorize("hasRole('CYBER_MANAGER')")
    public ResponseEntity<Submissions.Submission> passToEndorsement(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.passToEndorsement(id));
    }

    /**
     * Endorses the VA Submission Awaiting Endorsement: 200 with it Endorsed (Endorsed Late after the Quarter's last day).
     * A step-up action: refused with {@code step-up-required} unless the VA POC signed in again within five minutes.
     */
    @PostMapping("/api/submissions/{id}/endorse")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<Submissions.Submission> endorse(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.endorse(id));
    }

    /** The returns of the VA Submission, the latest first, each with what was Disputed then. */
    @GetMapping("/api/submissions/{id}/returns")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<List<Submissions.Return>> returns(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.returns(id));
    }

    @GetMapping("/api/submissions/{id}")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Submissions.Submission> submission(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.get(id));
    }
}
