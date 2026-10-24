package sg.annualva.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.Page;

/**
 * Each person's in-app notifications (story 92), for every role: only the caller's own are ever listed or marked read.
 */
@RestController
public class NotificationController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private static final String ANY_ROLE = "hasAnyRole('VA_POC','CYBER_MANAGER','SMO','AUDITOR')";

    private final Notifications notifications;

    NotificationController(Notifications notifications) {
        this.notifications = notifications;
    }

    /** The caller's notifications, newest first, with whether each is read and, for a VA POC pair, who handled it. */
    @GetMapping("/api/notifications")
    @PreAuthorize(ANY_ROLE)
    public ResponseEntity<Page<NotificationView>> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.mine(page, size));
    }

    /** Marks one of the caller's notifications read: 200 with it; another person's is 404. */
    @PostMapping("/api/notifications/{id}/read")
    @PreAuthorize(ANY_ROLE)
    public ResponseEntity<NotificationView> read(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.read(id));
    }
}
