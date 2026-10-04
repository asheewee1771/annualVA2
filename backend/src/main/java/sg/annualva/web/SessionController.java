package sg.annualva.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.config.AnnualVaProperties;

/**
 * What the SPA asks on load: is there a session, and what classification banner to show.
 * Anonymous by design (see SecurityConfig.PUBLIC_PATHS). The access module adds the signed-in
 * identity and roles.
 */
@RestController
public class SessionController {

    private final AnnualVaProperties properties;

    public SessionController(AnnualVaProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/session")
    public Map<String, Object> session() {
        return Map.of(
                "authenticated", false,
                "classification", properties.classification().banner());
    }
}
