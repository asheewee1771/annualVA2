package sg.annualva.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

    /** The same answer whether the resource is missing or the caller has no scope over it (ac-1). */
    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound() {
        return ProblemResponses.problem(HttpStatus.NOT_FOUND, "not-found", "There is no such resource.");
    }

    /** A sensitive command needs a fresh sign-in first (ADR 0004); the problem says where to start one. */
    @ExceptionHandler(StepUpRequiredException.class)
    ProblemDetail handleStepUpRequired(StepUpRequiredException ex) {
        ProblemDetail problem = ProblemResponses.problem(HttpStatus.FORBIDDEN, "step-up-required",
                "Sign in again to do this.");
        problem.setProperty("stepUpPath", ex.stepUpPath());
        return problem;
    }

    /** A method-level rule refused the caller: raised inside the controller, so the filter chain never sees it. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleForbidden() {
        return ProblemResponses.problem(HttpStatus.FORBIDDEN, "forbidden", "You are not allowed to do this.");
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
