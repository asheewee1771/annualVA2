package sg.annualva.web;

import org.springframework.http.HttpStatus;

/**
 * A domain module refusing a request with a fixed problem: a status, a problem type and a sentence written in the
 * source, never anything taken from the request or from a file (as-13). The same answer is given every time, whatever
 * the input was. It carries no cause and no stack trace.
 */
public final class RejectedRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String type;

    public RejectedRequestException(HttpStatus status, String type, String detail) {
        super(detail, null, false, false);
        this.status = status;
        this.type = type;
    }

    HttpStatus status() {
        return status;
    }

    String type() {
        return type;
    }
}
