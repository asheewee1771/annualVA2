package sg.annualva.dashboard;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Cyber Manager's action inbox, read-only. Only a Cyber Manager may call it (an SMO/Tech Lead or a VA POC is refused);
 * it lists the Systems assigned to the caller and nobody else's. It names no report and no Finding, only counts, so
 * reading it is not audited (as for the SMO inbox, ADR 0019).
 */
@RestController
public class CmInboxController {

    private final CmInbox inbox;

    CmInboxController(CmInbox inbox) {
        this.inbox = inbox;
    }

    /** @param cycle the VA Cycle's year; omitted for the Cycle running today */
    @GetMapping("/api/dashboards/cm-inbox")
    @PreAuthorize("hasRole('CYBER_MANAGER')")
    public CmInbox.Inbox cmInbox(@RequestParam(required = false) @Nullable Integer cycle) {
        return inbox.inbox(cycle);
    }
}
