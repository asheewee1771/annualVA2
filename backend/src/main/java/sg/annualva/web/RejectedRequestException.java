package sg.annualva.web;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A domain module refusing a request with a fixed problem: a status, a problem type and a sentence written in the
 * source, never anything taken from the request or from a file (as-13). The same answer is given every time, whatever
 * the input was. It carries no cause and no stack trace. A 400 about one field of the request body also names that
 * field (see {@link #invalidField}); the name is a constant of the source, never taken from the request.
 */
public final class RejectedRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String type;
    private final @Nullable String field;

    public RejectedRequestException(HttpStatus status, String type, String detail) {
        this(status, type, detail, null);
    }

    private RejectedRequestException(HttpStatus status, String type, String detail, @Nullable String field) {
        super(detail, null, false, false);
        this.status = status;
        this.type = type;
        this.field = field;
    }

    /** 400 {@code invalid-field}: the request body is wrong in one field, which the problem names ({@code field}). */
    public static RejectedRequestException invalidField(String field, String detail) {
        return new RejectedRequestException(HttpStatus.BAD_REQUEST, "invalid-field", detail, field);
    }

    HttpStatus status() {
        return status;
    }

    String type() {
        return type;
    }

    @Nullable String field() {
        return field;
    }
}
