package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import sg.annualva.config.DevLocal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import sg.annualva.web.RejectedRequestException;

/**
 * Step-up on a developer's machine (ticket 63, ADR 0058), with the same meaning it has with the single sign-on
 * (ADR 0004): a sensitive command still asks {@link StepUp#require} and is refused unless the session's {@code auth_time}
 * is within the freshness window. Starting a step-up remembers, in the session, the intent and the person that asked; the
 * local confirmation completes it only for that person, that intent and within the freshness window of starting it, once,
 * and then does what the identity provider's fresh sign-in does: moves {@code auth_time} to now and nothing else (the
 * session's limits stay as they were), through the same {@link StepUpSuccessHandler}. A confirmation that does not match
 * a started step-up changes nothing and is audited as refused.
 */
@Component
@DevLocal
class DevLocalStepUp implements StepUpStarter {

    static final String NOT_STARTED = "not-started";
    static final String INTENT_MISMATCH = "intent-mismatch";
    static final String EXPIRED = "expired";

    /** The session attribute that holds the started step-up ({@link StepUpIntent} is what OIDC keeps on its request). */
    private static final String STARTED = "annualva.devLocalStepUp";
    static final String CONFIRMATION_PAGE = "/dev-step-up";

    private final Clock clock;
    private final StepUpProperties properties;
    private final AccessAudit audit;
    private final StepUpSuccessHandler success;

    DevLocalStepUp(Clock clock, StepUpProperties properties, AccessAudit audit, StepUpSuccessHandler success) {
        this.clock = clock;
        this.properties = properties;
        this.audit = audit;
        this.success = success;
    }

    /** Remembers the step-up and says where the browser goes to confirm it (a page of the front end). */
    @Override
    public String begin(HttpServletRequest request, HttpServletResponse response, SessionUser user, String intent,
            String returnTo) {
        request.getSession().setAttribute(STARTED, new StepUpIntent(intent, returnTo, user.id(), clock.instant()));
        audit.stepUpStarted(user, intent);
        return request.getContextPath() + CONFIRMATION_PAGE + "?intent=" + intent + "&returnTo="
                + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    /**
     * Completes the started step-up of this intent.
     *
     * @return where the browser goes next
     * @throws RejectedRequestException 409 when there is no such step-up to complete; nothing has changed
     */
    String confirm(HttpServletRequest request, HttpServletResponse response, SessionUser user, String intent) {
        HttpSession session = request.getSession(false);
        StepUpIntent started = session != null && session.getAttribute(STARTED) instanceof StepUpIntent kept
                && kept.userId().equals(user.id()) ? kept : null;
        if (started == null) {
            throw refused(user, intent, NOT_STARTED);
        }
        if (!started.intent().equals(intent)) {
            throw refused(user, intent, INTENT_MISMATCH);
        }
        session.removeAttribute(STARTED);
        if (started.startedAt().plus(properties.freshnessWindow()).isBefore(clock.instant())) {
            throw refused(user, intent, EXPIRED);
        }
        success.refresh(request, response, user.withAuthTime(clock.instant()), intent);
        return started.returnTo();
    }

    private RejectedRequestException refused(SessionUser user, String intent, String reason) {
        audit.stepUpFailed(user.id(), intent, reason);
        return new RejectedRequestException(HttpStatus.CONFLICT, "step-up-not-started",
                "No step-up of this kind is waiting to be confirmed. Start it again from the action.");
    }
}
