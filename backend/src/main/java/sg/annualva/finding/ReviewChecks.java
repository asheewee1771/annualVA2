package sg.annualva.finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.submission.ReviewProgress;
import sg.annualva.submission.SubmitReadiness.Blocker;

/**
 * How far the Cyber Manager's review of a VA Submission is (stories 55 and 57), in one place, so that the return and the
 * pass to endorsement refuse by exactly what the review screen shows. It is a function of the Findings' counts and the Scan
 * Reports the readiness read already holds ({@link #outcome(FindingStore.Counts, Map)}); the reasons a pass is refused
 * with are, in order:
 *
 * <ol>
 *   <li>{@code findings-awaiting-review}: Findings whose current proposal the Cyber Manager has not yet confirmed or
 *       Disputed (or with none, which a submitted VA Submission never has).
 *   <li>{@code findings-disputed}: Findings whose current proposal the Cyber Manager Disputed.
 *   <li>for each Scan Report marked not applicable whose justification is not confirmed, {@code justification-awaiting-review}
 *       or {@code justification-disputed}.
 * </ol>
 *
 * Counts and fixed sentences only, never report content.
 */
@Component
class ReviewChecks implements ReviewProgress {

    private final ReviewStore reviews;
    private final FindingStore findings;
    private final ScanReportStore reports;

    ReviewChecks(ReviewStore reviews, FindingStore findings, ScanReportStore reports) {
        this.reviews = reviews;
        this.findings = findings;
        this.reports = reports;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome outcome(UUID submissionId) {
        return outcome(findings.counts(submissionId), reports.ofSubmission(submissionId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Disputed disputed(UUID submissionId) {
        return new Disputed(reviews.disputedFindings(submissionId), reviews.disputedReports(submissionId));
    }

    /** The outcome from the Findings' counts and the Scan Reports that have a row (one with none is uploaded). */
    static Outcome outcome(FindingStore.Counts counts, Map<ReportType, ScanReportStore.Slot> slots) {
        List<Blocker> unconfirmed = new ArrayList<>();
        long awaiting = counts.awaitingReview() + counts.unproposed();
        if (awaiting > 0) {
            unconfirmed.add(new Blocker("findings-awaiting-review", findingCount(awaiting) + " still to confirm or Dispute.",
                    null, List.of()));
        }
        if (counts.disputed() > 0) {
            unconfirmed.add(new Blocker("findings-disputed", findingCount(counts.disputed())
                    + " Disputed: return the VA Submission for rework, or review again.", null, List.of()));
        }
        boolean justificationDisputed = false;
        for (ReportType type : ReportType.values()) {
            ScanReportStore.Slot slot = slots.getOrDefault(type, ScanReportStore.Slot.UPLOADED);
            if (slot.mode() != ReportMode.NOT_APPLICABLE || slot.reviewState() == ReviewState.CONFIRMED) {
                continue;
            }
            String report = "The not-applicable justification of the " + type.title() + " Scan Report ";
            if (slot.reviewState() == ReviewState.DISPUTED) {
                justificationDisputed = true;
                unconfirmed.add(new Blocker("justification-disputed", report + "is Disputed: return the VA Submission "
                        + "for rework, or review it again.", type.name(), List.of()));
            } else {
                unconfirmed.add(new Blocker("justification-awaiting-review", report + "is still to confirm or Dispute.",
                        type.name(), List.of()));
            }
        }
        return new Outcome(counts.disputed() > 0 || justificationDisputed, unconfirmed);
    }

    private static String findingCount(long count) {
        return count + (count == 1 ? " Finding is" : " Findings are");
    }
}
