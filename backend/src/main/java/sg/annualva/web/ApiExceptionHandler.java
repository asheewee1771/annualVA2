package sg.annualva.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
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
 * with the exception. Report content never reaches an error body. A 403 it answers is first reported to the
 * {@link RefusalListener} (lm-4); it runs after the command's own transaction has ended, so the refusal is
 * recorded whether or not that transaction rolled back.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final RefusalListener refusals;

    ApiExceptionHandler(RefusalListener refusals) {
        this.refusals = refusals;
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemResponses.withTraceId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /** Spring's own text for a value that cannot be converted quotes the value; a request value is never echoed (as-13). */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "A request parameter has a value of the wrong form.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** The same answer whether the resource is missing or the caller has no scope over it (ac-1). */
    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound() {
        return ProblemResponses.problem(HttpStatus.NOT_FOUND, "not-found", "There is no such resource.");
    }

    /** A domain module refused the request with a fixed problem (its type and sentence are constants). */
    @ExceptionHandler(RejectedRequestException.class)
    ProblemDetail handleRejected(RejectedRequestException ex) {
        ProblemDetail problem = ProblemResponses.problem(ex.status(), ex.type(), ex.getMessage());
        if (ex.field() != null) {
            problem.setProperty("field", ex.field());
        }
        if (!ex.reasons().isEmpty()) {
            problem.setProperty("reasons", ex.reasons());
        }
        return problem;
    }

    /** A sensitive command needs a fresh sign-in first (ADR 0004); the problem says where to start one. */
    @ExceptionHandler(StepUpRequiredException.class)
    ProblemDetail handleStepUpRequired(StepUpRequiredException ex) {
        refusals.refused(new RefusalListener.Refusal(HttpStatus.FORBIDDEN.value(), "step-up-required", ex.intent()));
        ProblemDetail problem = ProblemResponses.problem(HttpStatus.FORBIDDEN, "step-up-required",
                "Sign in again to do this.");
        problem.setProperty("stepUpPath", ex.stepUpPath());
        return problem;
    }

    /** A method-level rule refused the caller: raised inside the controller, so the filter chain never sees it. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleForbidden() {
        refusals.refused(new RefusalListener.Refusal(HttpStatus.FORBIDDEN.value(), "forbidden"));
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
