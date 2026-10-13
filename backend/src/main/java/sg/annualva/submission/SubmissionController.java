package sg.annualva.submission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
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

    @GetMapping("/api/submissions/{id}")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<Submissions.Submission> submission(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.get(id));
    }
}
