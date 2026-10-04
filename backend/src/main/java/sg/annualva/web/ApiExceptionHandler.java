package sg.annualva.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One handler for every controller error (as-13). Framework exceptions become RFC 9457 problems
 * with the traceId added; anything unexpected becomes a generic 500 and is logged once, here,
 * with the exception. Report content never reaches an error body.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemResponses.withTraceId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.atError()
                .setMessage("Unhandled exception")
                .addKeyValue("event.action", "unhandled-exception")
                .setCause(ex)
                .log();
        return ProblemResponses.problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Something went wrong. Quote the reference when asking for help.");
    }
}
