package sg.annualva.access;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * VA POC cannot lift a suspension of their own role or suspend themselves out of segregation of duties. The writes go
 * through {@link RoleLapse}, the writer of a grant's state; each change and each refusal is audited with the grant id.
 */
@Service
public class RoleHolders {

    static final String SUSPEND_INTENT = "role-suspend";
    static final String RESTORE_INTENT = "role-restore";

    /** The roles a review may suspend: those held for a System. */
    private static final Set<Role> PER_SYSTEM = Set.of(Role.SMO, Role.CYBER_MANAGER);

    private final RoleGrantStore grants;
    private final RoleLapse lapse;
    private final StepUp stepUp;
    private final CurrentUser user;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;

    RoleHolders(RoleGrantStore grants, RoleLapse lapse, StepUp stepUp, CurrentUser user, AuditLog audit, AuditActor actor,
            Clock clock) {
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
     * Suspends an SMO/Tech Lead or Cyber Manager grant, with a fresh sign-in.
     *
     * @throws ResourceNotFoundException when there is no such grant, or it was revoked
     * @throws RejectedRequestException 403 {@code own-grant} for the caller's own; 409 {@code not-for-a-system} for a
     *     VA POC or Auditor grant, {@code already-suspended} for one suspended already
     */
    @Transactional
    public RoleHolder suspend(UUID grantId) {
        stepUp.require(SUSPEND_INTENT);
        RoleGrant grant = changeable(grantId, AuditAction.ROLE_SUSPENDED);
        if (!PER_SYSTEM.contains(grant.role())) {
            throw refused(AuditAction.ROLE_SUSPENDED, grant, HttpStatus.CONFLICT, "not-for-a-system",
                    "Only a role held for a System can be suspended.");
        }
        if (!lapse.suspend(grant, SuspensionCause.REVIEW, user.id(), clock.instant())) {
            throw refused(AuditAction.ROLE_SUSPENDED, grant, HttpStatus.CONFLICT, "already-suspended",
                    "This role is already suspended.");
        }
        return grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Lifts a grant's suspension, with a fresh sign-in.
     *
     * @throws ResourceNotFoundException when there is no such grant, or it was revoked
     * @throws RejectedRequestException 403 {@code own-grant} for the caller's own; 409 {@code disabled-at-identity-provider}
     *     while the identity provider last reported the person disabled, {@code not-suspended} for one not suspended
     */
    @Transactional
    public RoleHolder restore(UUID grantId) {
        stepUp.require(RESTORE_INTENT);
        RoleGrant grant = changeable(grantId, AuditAction.ROLE_RESTORED);
        RoleHolder holder = grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
        if (holder.disabledAtIdentityProvider()) {
            throw refused(AuditAction.ROLE_RESTORED, grant, HttpStatus.CONFLICT, "disabled-at-identity-provider",
                    "The identity provider reports this person disabled. Restore the role once they can sign in again.");
        }
        if (!lapse.restore(grant)) {
            throw refused(AuditAction.ROLE_RESTORED, grant, HttpStatus.CONFLICT, "not-suspended",
                    "This role is not suspended.");
        }
        return grants.holder(grantId).orElseThrow(ResourceNotFoundException::new);
    }

    /** The grant, when it exists and is not the caller's own. */
    private RoleGrant changeable(UUID grantId, AuditAction action) {
        RoleGrant grant = grants.live(grantId).orElseThrow(ResourceNotFoundException::new);
        if (user.id().equals(grant.userId())) {
            throw refused(action, grant, HttpStatus.FORBIDDEN, "own-grant",
                    "You cannot suspend or restore a role of your own. Ask the other VA POC.");
        }
        return grant;
    }

    /** Audits the refusal in a transaction of its own, which the refused command's rollback leaves standing. */
    private RejectedRequestException refused(AuditAction action, RoleGrant grant, HttpStatus status, String reason,
            String sentence) {
        audit.recordIndependently(actor.attribute(grant.describe(AuditEvent.of(action, AuditOutcome.REFUSED)))
                .with(DetailKey.REASON, reason));
        return new RejectedRequestException(status, reason, sentence);
    }
}
