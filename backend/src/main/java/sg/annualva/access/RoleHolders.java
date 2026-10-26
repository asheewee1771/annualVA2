package sg.annualva.access;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The role store as the VA POCs' role-holder review sees it (IM8 ac-4, ticket 47, ADR 0056): every grant not revoked, and
 * the review's one change, suspending a person's role for a System until a Register upload names someone else, or
 * restoring it. Both are step-up actions (ac-2, "changing roles") and both are refused for the caller's own grant, so a
 * VA POC cannot lift a suspension of their own role or suspend themselves out of segregation of duties. What may be done
 * to a grant is {@link RoleHolder#suspendableBy} and {@link RoleHolder#restorableBy}; a refusal says which part failed.
 * The writes go through {@link RoleLapse}, the writer of a grant's state; each change and each refusal is audited with the
 * grant id.
 */
@Service
public class RoleHolders {

    static final String SUSPEND_INTENT = "role-suspend";
    static final String RESTORE_INTENT = "role-restore";

    private static final String OWN_GRANT = "You cannot suspend or restore a role of your own. Ask the other VA POC.";

    private final RoleGrantStore grants;
    private final RoleLapse lapse;
    private final StepUp stepUp;
    private final CurrentUser user;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    RoleHolders(RoleGrantStore grants, RoleLapse lapse, StepUp stepUp, CurrentUser user, AuditLog audit, AuditActor actor,
            Clock clock, ApplicationEventPublisher events) {
        this.events = events;
        this.grants = grants;
        this.lapse = lapse;
        this.stepUp = stepUp;
        this.user = user;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    /** Every grant that has not been revoked, oldest first. */
    public List<RoleHolder> list() {
        return grants.holders();
    }

    /**
     * The suspended Register grants of these Systems, for the Register preview (ADR 0049 item 2): a change that only
     * replaces a suspended SMO/Tech Lead or Cyber Manager is applied even on a System with in-flight work, as the one
     * exception to the hold-back, because a suspension otherwise leaves the System with nobody to act.
     */
    @Transactional(readOnly = true)
    public List<RoleGrant> suspendedOn(Collection<UUID> systemIds) {
        return grants.suspendedOn(systemIds);
    }

    /**
     * Whether the person the Register names for this role on this System cannot act at its step, because their grant for
     * it is suspended or lapsed (decided 2026-10-26): the notifications then tell the VA POCs instead of them. A person
     * with no grant yet (named by an apply whose grants are still being written) can.
     */
    @Transactional(readOnly = true)
    public boolean cannotAct(Role role, String email, UUID systemId) {
        return grants.liveByKey(role, email, systemId)
                .map(grant -> grant.suspendedAt() != null || grant.lapsedAt() != null)
                .orElse(false);
    }

    /**
     * Suspends an SMO/Tech Lead or Cyber Manager grant, with a fresh sign-in.
     *
     * @throws ResourceNotFoundException when there is no such grant, or it was revoked
     * @throws RejectedRequestException 403 {@code own-grant} for the caller's own; 409 {@code not-for-a-system} for a
     *     VA POC or Auditor grant, {@code already-suspended} for one suspended already
     */
    @Transactional
    public RoleHolder suspend(UUID grantId) {
        stepUp.require(SUSPEND_INTENT);
        UUID caller = user.id();
        RoleHolder holder = grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
        if (!holder.suspendableBy(caller)) {
            throw refused(AuditAction.ROLE_SUSPENDED, holder.grant(), holder.heldBy(caller)
                    ? new Refusal(HttpStatus.FORBIDDEN, "own-grant", OWN_GRANT)
                    : !Role.PER_SYSTEM.contains(holder.grant().role())
                            ? new Refusal(HttpStatus.CONFLICT, "not-for-a-system",
                                    "Only a role held for a System can be suspended.")
                            : new Refusal(HttpStatus.CONFLICT, "already-suspended", "This role is already suspended."));
        }
        lapse.suspend(holder.grant(), SuspensionCause.REVIEW, caller, clock.instant());
        return grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Lifts a grant's suspension, with a fresh sign-in.
     *
     * @throws ResourceNotFoundException when there is no such grant, or it was revoked
     * @throws RejectedRequestException 403 {@code own-grant} for the caller's own; 409 {@code not-suspended} for one not
     *     suspended, {@code disabled-at-identity-provider} while the identity provider last reported the person disabled
     */
    @Transactional
    public RoleHolder restore(UUID grantId) {
        stepUp.require(RESTORE_INTENT);
        UUID caller = user.id();
        RoleHolder holder = grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
        if (!holder.restorableBy(caller)) {
            throw refused(AuditAction.ROLE_RESTORED, holder.grant(), holder.heldBy(caller)
                    ? new Refusal(HttpStatus.FORBIDDEN, "own-grant", OWN_GRANT)
                    : holder.grant().suspendedAt() == null
                            ? new Refusal(HttpStatus.CONFLICT, "not-suspended", "This role is not suspended.")
                            : new Refusal(HttpStatus.CONFLICT, "disabled-at-identity-provider",
                                    "The identity provider reports this person disabled. Restore the role once they can "
                                            + "sign in again."));
        }
        lapse.restore(holder.grant());
        RoleGrant restored = holder.grant();
        if (restored.systemId() != null) {
            events.publishEvent(new RoleHolderChanged(restored.systemId(), restored.role(), caller, clock.instant()));
        }
        return grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
    }

    /** Why a review command was refused: a status, a fixed problem type and a sentence written here. */
    private record Refusal(HttpStatus status, String reason, String sentence) {}

    /** Audits the refusal in a transaction of its own, which the refused command's rollback leaves standing. */
    private RejectedRequestException refused(AuditAction action, RoleGrant grant, Refusal refusal) {
        audit.recordIndependently(actor.attribute(grant.describe(AuditEvent.of(action, AuditOutcome.REFUSED)))
                .with(DetailKey.REASON, refusal.reason()));
        return new RejectedRequestException(refusal.status(), refusal.reason(), refusal.sentence());
    }
}
