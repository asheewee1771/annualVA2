package sg.annualva.dashboard;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import sg.annualva.finding.Findings;
import sg.annualva.finding.ReviewCounts;
import sg.annualva.submission.SubmissionStep;
import sg.annualva.submission.Submissions;

/**
 * What the Cyber Manager's dashboards add to each System (stories 48 and 58): how many Findings of its VA Submission they
 * confirmed and how many await their review, and whether segregation of duties will refuse their review, because they
 * acted on that VA Submission at another step, as its SMO/Tech Lead say (ticket 28 refuses the review itself).
 */
@Component
class ReviewMarkers {

    /** The markers of one System; a Not Started one has none. */
    record Marker(long confirmed, long awaitingReview, boolean sodConflict) {

        static final Marker NONE = new Marker(0, 0, false);
    }

    private final Findings findings;
    private final Submissions submissions;

    ReviewMarkers(Findings findings, Submissions submissions) {
        this.findings = findings;
        this.submissions = submissions;
    }

    /** The markers of these rows, by VA Submission; a row with no VA Submission is absent. */
    Map<UUID, Marker> of(Collection<DashboardSystems.Row> rows) {
        List<UUID> ids = rows.stream().map(DashboardSystems.Row::submissionId).filter(Objects::nonNull).toList();
        Map<UUID, ReviewCounts> counts = findings.reviewCounts(ids);
        Set<UUID> conflicted = submissions.actedAtAnotherStep(ids, SubmissionStep.REVIEW);
        return ids.stream().collect(Collectors.toUnmodifiableMap(Function.identity(), id -> {
            ReviewCounts count = counts.getOrDefault(id, ReviewCounts.NONE);
            return new Marker(count.confirmed(), count.awaitingReview(), conflicted.contains(id));
        }));
    }

    /** The markers of one row. */
    static Marker of(Map<UUID, Marker> markers, DashboardSystems.Row row) {
        UUID submission = row.submissionId();
        return submission == null ? Marker.NONE : markers.getOrDefault(submission, Marker.NONE);
    }
}
