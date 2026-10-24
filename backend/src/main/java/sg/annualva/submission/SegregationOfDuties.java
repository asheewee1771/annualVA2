package sg.annualva.submission;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.SystemScope;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.DetailKey;
import sg.annualva.web.RejectedRequestException;

/**
 * Segregation of duties (story 58, ADR 0035), the one place its rule lives: a person may not act at more than one step of
 * the same VA Submission. A person is refused a step when they acted at another step of it ({@code
 * submission_participation}), or when the Register assigns them to another step of its System even before they act: the
 * System's SMO/Tech Lead counts as its submitter and its Cyber Manager as its reviewer ({@link SubmissionStep#need()}).
 *
 * <p>Every command at a step calls {@link #require} once, before it records the step: start, submit and the reviews today,
 * and every later step command (return, pass to endorsement, endorse). {@code ArchitectureTest} keeps it so: a method that
 * records a step must call {@link #require}. The read models mark the same VA Submissions through {@link #conflictedAt},
 * so a marker and a refusal never disagree.
 */
@Component
class SegregationOfDuties {

    private final SubmissionStore store;
    private final AccessGuard guard;
    private final CurrentUser user;
    private final AuditLog audit;

    SegregationOfDuties(SubmissionStore store, AccessGuard guard, CurrentUser user, AuditLog audit) {
        this.store = store;
        this.guard = guard;
        this.user = user;
        this.audit = audit;
    }

    /**
     * Passes silently when segregation of duties allows the caller this step; otherwise audits the refusal in a
     * transaction of its own (ADR 0014), as {@code refusedAs} with {@code reason} {@code sod-conflict}, and throws 403
     * {@code sod-conflict} with a fixed sentence that names no person and no step.
     *
     * @param systemId the System the VA Submission belongs to
     * @param submissionId the VA Submission; null before it exists (a start), when only the assignment can conflict
     * @param refusedAs the entry a refusal is recorded as: the command's own action, REFUSED, attributed to the caller and
     *     about the System (and the VA Submission when there is one)
     */
    void require(SubmissionStep step, UUID systemId, @Nullable UUID submissionId, AuditEvent refusedAs) {
        Set<UUID> assigned = assignedAtAnotherStep(step);
        boolean conflicted = submissionId == null
                ? assigned.contains(systemId)
                : !store.conflicted(List.of(submissionId), user.id(), step, assigned).isEmpty();
        if (conflicted) {
            audit.recordIndependently(refusedAs.with(DetailKey.REASON, "sod-conflict"));
            throw new RejectedRequestException(HttpStatus.FORBIDDEN, "sod-conflict",
                    "Segregation of duties: you acted at, or are assigned to, another step of this VA Submission, so "
                    + "you cannot act at this one.");
        }
    }

    /** The VA Submissions among these where the rule refuses the caller this step, for a read model that marks them. */
    Set<UUID> conflictedAt(Collection<UUID> submissionIds, SubmissionStep step) {
        return store.conflicted(submissionIds, user.id(), step, assignedAtAnotherStep(step));
    }

    /**
     * The Systems the caller is assigned to at a step other than this one. A scope over every System (a VA POC's) is not an
     * assignment: holding the VA POC role does not assign a person to the endorsing step of any System; only endorsing,
     * returning or deciding an Exemption on a VA Submission counts, as {@code submission_participation} (ADR 0036 item 5).
     * Ticket 34, which adds the endorsing step, gives it no Register assignment; until then meeting such a scope here is a
     * programming error, not a silent "assigned to nothing".
     */
    private Set<UUID> assignedAtAnotherStep(SubmissionStep step) {
        Set<UUID> systems = new HashSet<>();
        for (SubmissionStep other : SubmissionStep.values()) {
            if (other != step) {
                SystemScope scope = guard.scope(other.need());
                if (scope.all()) {
                    throw new IllegalStateException("a step assigned by a scope over every System counts by acting only "
                            + "(ADR 0036 item 5): give it no Register assignment");
                }
                systems.addAll(scope.ids());
            }
        }
        return systems;
    }
}
