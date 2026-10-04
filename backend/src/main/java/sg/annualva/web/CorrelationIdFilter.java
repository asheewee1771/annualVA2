package sg.annualva.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id (lm-15): taken from a well-formed {@code X-Correlation-Id}
 * header, otherwise generated. It is put in the MDC as {@code trace.id} for every log and audit
 * line, echoed in the response header, and returned as {@code traceId} in error bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "trace.id";
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = correlationIdFor(request.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        try {
            response.setHeader(HEADER, id);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String correlationIdFor(String header) {
        if (header != null && WELL_FORMED.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    /** The current request's correlation id, or {@code "none"} outside a request. */
    public static String current() {
        return Objects.requireNonNullElse(MDC.get(MDC_KEY), "none");
    }
}
