package sg.annualva.access;

import java.io.Serial;
import org.springframework.security.core.AuthenticationException;

/** A sign-in the application refuses after the identity provider accepted it: no session is created. */
public final class SignInRefusedException extends AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reason;

    /** @param reason a fixed code for the front end and the ECS event: never user data */
    public SignInRefusedException(String reason) {
        super("Sign-in refused: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
