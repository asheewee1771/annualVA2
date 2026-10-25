package sg.annualva.retention;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The disposal lists (story 87): the VA POCs' alone (the permission matrix, "Approve disposal"). */
@RestController
public class DisposalController {

    private final DisposalLists lists;

    DisposalController(DisposalLists lists) {
        this.lists = lists;
    }

    /** The newest disposal lists, newest first, each with its items. */
    @GetMapping("/api/disposal-lists")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<List<DisposalLists.ListView>> lists() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lists.lists());
    }

    /** Approves the list waiting for approval (a step-up action); the executor then disposes of it. */
    @PostMapping("/api/disposal-lists/{id}/approve")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<DisposalLists.ListView> approve(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lists.approve(id));
    }
}
