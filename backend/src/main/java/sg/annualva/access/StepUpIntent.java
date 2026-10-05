package sg.annualva.access;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * What a step-up in progress is for, kept in the session on the authorization request it started (so
 * it is consumed by that request's callback and by no other).
 *
 * @param intent the fixed code of the sensitive action
 * @param returnTo a same-origin path made of safe characters only; where the callback sends the browser
 * @param userId the person who must complete it
 * @param startedAt when it started, by the application clock; the new {@code auth_time} must not predate this
 */
record StepUpIntent(String intent, String returnTo, UUID userId, Instant startedAt) implements Serializable {

    /** The authorization request attribute that holds it. */
    static final String ATTRIBUTE = "annualva.stepUp";

    @Serial
    private static final long serialVersionUID = 1L;
}
