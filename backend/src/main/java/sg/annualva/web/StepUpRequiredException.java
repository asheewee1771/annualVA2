package sg.annualva.web;

import java.io.Serial;

/**
 * A sensitive command was refused because the caller has not signed in again recently (IM8 ac-2,
 * ADR 0004). It becomes a 403 problem of type {@code step-up-required} carrying the path that
 * starts the fresh sign-in; the command is not run and is never replayed afterwards.
 */
public final class StepUpRequiredException extends RuntimeException {

    /** Where the browser starts a step-up; the intent names the sensitive action. */
    public static final String START_PATH = "/auth/step-up";

    @Serial
    private static final long serialVersionUID = 1L;

    private final String intent;

    /** @param intent the fixed code of the sensitive action, such as {@code register-apply}: never user data */
    public StepUpRequiredException(String intent) {
        super(null, null, false, false);
        this.intent = intent;
    }

    /** The fixed code of the sensitive action that was refused. */
    public String intent() {
        return intent;
    }

    /** The path the front end sends the person to, without a return route (the client adds that). */
    public String stepUpPath() {
        return START_PATH + "?intent=" + intent;
    }
}
