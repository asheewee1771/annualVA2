package sg.annualva.dashboard;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SMO/Tech Lead's inbox, read-only. Only an SMO/Tech Lead may call it (a Cyber Manager or VA POC is refused); it
 * lists the caller's own Systems and nobody else's. It names no report and no Finding, so reading it is not audited
 * (as for the Register list, ADR 0018).
 */
@RestController
public class SmoInboxController {

    private final SmoInbox inbox;

    SmoInboxController(SmoInbox inbox) {
        this.inbox = inbox;
    }

    /** @param cycle the VA Cycle's year; omitted for the Cycle running today */
    @GetMapping("/api/dashboards/smo-inbox")
    @PreAuthorize("hasRole('SMO')")
    public SmoInbox.Inbox smoInbox(@RequestParam(required = false) @Nullable Integer cycle) {
        return inbox.inbox(cycle);
    }
}
