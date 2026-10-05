package sg.annualva.web;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bootstraps the synchronizer CSRF token for the SPA. The token lives in the server session;
 * state-changing calls send it back in the named header.
 */
@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    @PreAuthorize("permitAll()")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName(),
                "token", token.getToken());
    }
}
