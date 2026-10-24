package sg.annualva.submission;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing a VA Cycle early (stories 17 and 68): a VA POC's command, after a fresh sign-in. It lives with the submissions
 * because the close moves every unfinished VA Submission of the Cycle to Not Endorsed; the calendar's own reads stay in
 * the cycle module.
 */
@RestController
public class CycleCloseController {

    private final CycleLifecycle lifecycle;

    CycleCloseController(CycleLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Closes the VA Cycle now: 200 with its last open day, when it closed and how many VA Submissions and Systems it made
     * Not Endorsed; 409 while its Q4 is running or once it is closed; 404 for a year the app does not run. A step-up
     * action: refused with {@code step-up-required} unless the VA POC signed in again within five minutes.
     */
    @PostMapping("/api/cycles/{year}/close")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<CycleLifecycle.Closed> close(@PathVariable int year) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lifecycle.close(year));
    }
}
