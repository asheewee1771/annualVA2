package sg.annualva.register;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The in-flight work a Register change touches (stories 5 to 7, ADR 0048): the VA Submissions of a System that are not
 * in a final state. The VA Submissions are kept by the submission module, which sits above this one, so it implements
 * this port, and the preview and the apply ask it without reaching up. Every method runs in the caller's transaction,
 * for a VA POC, whose scope is every System (ADR 0013).
 */
public interface InFlightWork {

    /** The in-flight VA Submissions of these Systems, by System, holding nothing: for a preview. */
    Map<UUID, List<Work>> of(Collection<UUID> systemIds);

    /**
     * The same for an apply, holding until the transaction ends every VA Cycle the app runs on the instant's business date
     * against a start, and these VA Submissions against every other command, so what the apply was checked against is
     * what it changes.
     */
    Map<UUID, List<Work>> hold(Collection<UUID> systemIds, Instant now);

    /**
     * Withdraws these VA Submissions, of Systems the apply has just retired (story 7): each becomes Withdrawn, is audited
     * as the apply's caller's with the preview, and is announced to the notifications. One that has reached a final state
     * meanwhile is left alone (the caller holds them, so none can).
     */
    void withdraw(Collection<UUID> submissionIds, UUID previewId, Instant now);

    /**
     * The apply has just moved a System to another Quarter: a Not Started System's Overdue flag follows the window of its
     * new Quarter (reviewer decisions, 2026-10-23), so it is cleared in each Cycle whose new Submission Window has not
     * passed, and set again by the sweep when it does.
     */
    void moved(UUID systemId, Instant now);

    /**
     * A VA Submission that is not in a final state.
     *
     * @param cycle its VA Cycle's year
     * @param state its state as the table holds it
     * @param filesScanning whether any of its files is still on its way through the checks (decision 2026-10-18)
     * @param submitters the lower-case emails of the people who acted at its submitting step (started or submitted it)
     * @param reviewers the same for its reviewing step
     * @param endorsers the same for its endorsing step
     */
    record Work(UUID submissionId, int cycle, String state, boolean filesScanning, Set<String> submitters,
            Set<String> reviewers, Set<String> endorsers) {

        public Work {
            submitters = Set.copyOf(submitters);
            reviewers = Set.copyOf(reviewers);
            endorsers = Set.copyOf(endorsers);
        }

        /**
         * Ids, codes and the year only: the emails, which are never logged, audited or answered, stay out of any log line
         * a framework writes about it.
         */
        @Override
        public String toString() {
            return "Work[submissionId=" + submissionId + ", cycle=" + cycle + ", state=" + state + "]";
        }
    }
}
