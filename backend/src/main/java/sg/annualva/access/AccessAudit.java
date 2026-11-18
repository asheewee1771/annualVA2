package sg.annualva.access;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.StepUpRefusal;
import sg.annualva.web.RefusalListener;

/**
 * What the access module tells the audit log (IM8 lm-4): sign-in, sign-out, a failed sign-in, each step of a
 * step-up and every request refused with a 401 or 403. Each is written in a transaction of its own, because no
 * domain transaction owns these and a refusal must be recorded even when the command it refused had rolled back.
 *
 * <p>An entry names the person by user id and their roles, never by email or name, and carries only fixed codes
 * (the reason, the intent, the status). A failure to write is logged and swallowed: a request that was going to be
 * refused is still refused, and the sign-in machinery does not depend on the audit table being writable.
 */
@Component
class AccessAudit implements RefusalListener {

    private static final Logger log = LoggerFactory.getLogger(AccessAudit.class);

    private final AuditLog audit;
    private final RoleResolver roles;
    private final KeySignals signals;

    AccessAudit(AuditLog audit, RoleResolver roles, KeySignals signals) {
        this.signals = signals;
        this.audit = audit;
        this.roles = roles;
    }

    void signedIn(SessionUser user) {
        write(by(AuditEvent.of(AuditAction.SIGN_IN, AuditOutcome.SUCCESS), user.id()));
    }

    void signedOut(SessionUser user) {
        write(by(AuditEvent.of(AuditAction.SIGN_OUT, AuditOutcome.SUCCESS), user.id()));
    }

    /**
     * No actor: a refused sign-in has not proved who the person is.
     *
     * @param reason a fixed code such as {@code mfa-not-evidenced}
     * @param errorCode one of the OAuth 2.0 error codes the sign-in failure handler knows, or {@code other}
     * @param outcome REFUSED when the rules said no, FAILED when the identity provider failed
     */
    void signInFailed(String reason, String errorCode, AuditOutcome outcome) {
        write(AuditEvent.of(AuditAction.SIGN_IN_FAILED, outcome)
                .with(DetailKey.REASON, reason)
                .with(DetailKey.ERROR_CODE, errorCode));
    }

    void stepUpStarted(SessionUser user, String intent) {
        write(by(AuditEvent.of(AuditAction.STEP_UP_STARTED, AuditOutcome.SUCCESS), user.id())
                .with(DetailKey.INTENT, intent));
    }

    void stepUpFullSignIn(SessionUser user, String intent) {
        write(by(AuditEvent.of(AuditAction.STEP_UP_FULL_SIGN_IN, AuditOutcome.SUCCESS), user.id())
                .with(DetailKey.INTENT, intent));
    }

    void stepUpCompleted(SessionUser user, String intent) {
        write(by(AuditEvent.of(AuditAction.STEP_UP_OK, AuditOutcome.SUCCESS), user.id())
                .with(DetailKey.INTENT, intent));
    }

    /** @param userId the person whose session the step-up was for, when the session still names one */
    void stepUpFailed(@Nullable UUID userId, String intent, String reason) {
        signals.stepUpRefused(StepUpRefusal.FAILED);
        write(by(AuditEvent.of(AuditAction.STEP_UP_FAILED, AuditOutcome.REFUSED), userId)
                .with(DetailKey.INTENT, intent)
                .with(DetailKey.REASON, reason));
    }

    /** Called as the 401 or 403 is written; the caller is whoever the request's security context names, if anyone. */
    @Override
    public void refused(Refusal refusal) {
        if ("step-up-required".equals(refusal.reason())) {
            signals.stepUpRefused(StepUpRefusal.REQUIRED);
        }
        AuditEvent event = AuditEvent.of(AuditAction.REQUEST_REFUSED, AuditOutcome.REFUSED)
                .with(DetailKey.STATUS, refusal.status())
                .with(DetailKey.REASON, refusal.reason());
        if (refusal.intent() != null) {
            event = event.with(DetailKey.INTENT, refusal.intent());
        }
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller instanceof RoleAuthentication resolved) {
            event = event.by(resolved.getPrincipal().id(), RoleAuthentication.audited(resolved.roles()));
        } else if (caller instanceof SessionAuthentication session) {
            event = by(event, session.getPrincipal().id());
        }
        write(event);
    }

    /** The same event, by this person, with the roles they hold now. */
    private AuditEvent by(AuditEvent event, @Nullable UUID userId) {
        return userId == null ? event : event.by(userId, roles.rolesOf(userId));
    }

    private void write(AuditEvent event) {
        try {
            audit.recordIndependently(event);
        } catch (RuntimeException failure) {
            log.atError()
                    .setMessage("An audit entry could not be written")
                    .addKeyValue("event.action", "audit-write-failed")
                    .addKeyValue("labels.audit_action", event.action().name())
                    // The type only: a database error's message can quote the row it refused.
                    .addKeyValue("error.type", failure.getClass().getSimpleName())
                    .log();
        }
    }
}
