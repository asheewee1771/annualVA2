package sg.annualva.register;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Register's Systems, read-only: every System for a VA POC, only their own for an SMO/Tech Lead or Cyber Manager
 * (the service filters by the caller's scope; ADR 0013). An Auditor has no System scope yet and is refused.
 */
@RestController
public class SystemsController {

    private final RegisterSystems systems;

    SystemsController(RegisterSystems systems) {
        this.systems = systems;
    }

    @GetMapping("/api/systems")
    @PreAuthorize("hasAnyRole('VA_POC','SMO','CYBER_MANAGER')")
    public List<RegisterSystems.SystemView> systems() {
        return systems.list();
    }
}
