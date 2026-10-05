package sg.annualva.access;

import java.io.Serial;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

/**
 * A step-up round trip that did not prove a fresh sign-in: the session is kept, but not made fresh. It
 * extends the exception type the provider manager rethrows at once, so the standard sign-in provider
 * listed after {@link StepUpAuthenticationProvider} never retries a code the step-up already spent.
 */
final class StepUpRefusedException extends InternalAuthenticationServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final StepUpIntent started;
    private final String reason;

    /** @param reason a fixed code for the front end and the ECS event: never user data */
    StepUpRefusedException(StepUpIntent started, String reason, Throwable cause) {
        super("Step-up refused: " + reason, cause);
        this.started = started;
        this.reason = reason;
    }

    StepUpIntent started() {
        return started;
    }

    String reason() {
        return reason;
    }
}
