package sg.annualva.finding;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.submission.ReviewProgress;
import sg.annualva.submission.SubmitReadiness.Blocker;

/**
 * How far the Cyber Manager's review of a VA Submission is (stories 55 and 57), in one place, so that the pass to
 * endorsement refuses with exactly what the review screen shows, and the return attaches exactly what is Disputed. In this
 * order:
 *
 * <ol>
 *   <li>{@code findings-awaiting-review}: Findings whose current proposal the Cyber Manager has not yet confirmed or
 *       Disputed.
 *   <li>{@code findings-disputed}: Findings whose current proposal the Cyber Manager Disputed.
 *   <li>for each Scan Report marked not applicable whose justification is not confirmed, {@code justification-awaiting-review}
 *       or {@code justification-disputed}.
 * </ol>
 *
 * Counts and fixed sentences only, never report content.
 */
@Component
class ReviewChecks implements ReviewProgress {

    private final ReviewStore store;

    ReviewChecks(ReviewStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Blocker> unconfirmed(UUID submissionId) {
        ReviewStore.UnconfirmedFindings findings = store.unconfirmedFindings(submissionId);
        List<Blocker> unconfirmed = new ArrayList<>();
        if (findings.awaiting() > 0) {
            unconfirmed.add(new Blocker("findings-awaiting-review", findingCount(findings.awaiting())
                    + " still to confirm or Dispute.", null, List.of()));
        }
        if (findings.disputed() > 0) {
            unconfirmed.add(new Blocker("findings-disputed", findingCount(findings.disputed())
                    + " Disputed: return the VA Submission for rework, or review again.", null, List.of()));
        }
        for (ReviewStore.UnconfirmedJustification justification : store.unconfirmedJustifications(submissionId)) {
            String report = "The not-applicable justification of the " + justification.type().title() + " Scan Report ";
            unconfirmed.add(justification.disputed()
                    ? new Blocker("justification-disputed", report + "is Disputed: return the VA Submission for "
                            + "rework, or review it again.", justification.type().name(), List.of())
                    : new Blocker("justification-awaiting-review", report + "is still to confirm or Dispute.",
                            justification.type().name(), List.of()));
        }
        return unconfirmed;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Disputed> disputed(UUID submissionId) {
        return store.disputed(submissionId);
    }

    private static String findingCount(long count) {
        return count + (count == 1 ? " Finding is" : " Findings are");
    }
}
