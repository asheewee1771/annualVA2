package sg.annualva.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes RFC 9457 problem responses for refusals that happen in the security filter chain,
 * before any controller runs (as-13: no internal detail; every body carries the traceId).
 */
@Component
public class ProblemResponses {

    public static final String TYPE_BASE = "https://annualva/problems/";

    private final ObjectMapper objectMapper;

    public ProblemResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, exception) -> write(response,
                problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
    }

    /** 403s: a missing or stale CSRF token gets its own problem type so the SPA can refresh the token. */
    public AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandler csrfRejected = (request, response, exception) -> write(response,
                problem(HttpStatus.FORBIDDEN, "csrf-rejected",
                        "The request did not carry a valid CSRF token. Reload and try again."));
        AccessDeniedHandler forbidden = (request, response, exception) -> write(response,
                problem(HttpStatus.FORBIDDEN, "forbidden", "You are not allowed to do this."));
        LinkedHashMap<Class<? extends AccessDeniedException>, AccessDeniedHandler> byType = new LinkedHashMap<>();
        byType.put(CsrfException.class, csrfRejected);
        return new DelegatingAccessDeniedHandler(byType, forbidden);
    }

    /** A problem with the fixed type, title, status and the current correlation id. */
    public static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(status.getReasonPhrase());
        return withTraceId(problem);
    }

    public static ProblemDetail withTraceId(ProblemDetail problem) {
        problem.setProperty("traceId", CorrelationIdFilter.current());
        return problem;
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
