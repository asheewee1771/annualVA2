package sg.annualva.web;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A domain module refusing a request with a fixed problem: a status, a problem type and a sentence written in the
 * source, never anything taken from the request or from a file (as-13). The same answer is given every time, whatever
 * the input was. It carries no cause and no stack trace. A 400 about one field of the request body also names that
 * field (see {@link #invalidField}); the name is a constant of the source, never taken from the request. A refusal for
 * several reasons at once lists them (see {@link #withReasons}).
 */
public final class RejectedRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String type;
    private final @Nullable String field;
    private final transient List<?> reasons;

    public RejectedRequestException(HttpStatus status, String type, String detail) {
        this(status, type, detail, null, List.of());
    }

    private RejectedRequestException(HttpStatus status, String type, String detail, @Nullable String field,
            List<?> reasons) {
        super(detail, null, false, false);
        this.status = status;
        this.type = type;
        this.field = field;
        this.reasons = List.copyOf(reasons);
    }

    /** 400 {@code invalid-field}: the request body is wrong in one field, which the problem names ({@code field}). */
    public static RejectedRequestException invalidField(String field, String detail) {
        return new RejectedRequestException(HttpStatus.BAD_REQUEST, "invalid-field", detail, field, List.of());
    }

    /**
     * A refusal for every one of these reasons, which the problem lists ({@code reasons}). Each reason is a record of fixed
     * codes and sentences, and at most names files by the name the person gave them: never report content.
     */
    public static RejectedRequestException withReasons(HttpStatus status, String type, String detail, List<?> reasons) {
        return new RejectedRequestException(status, type, detail, null, reasons);
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

    List<?> reasons() {
        return reasons;
    }
}
