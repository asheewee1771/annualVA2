package sg.annualva.finding;

/**
 * How far the Cyber Manager's review of a VA Submission's Findings is, for a dashboard (story 48). Counts only.
 *
 * @param confirmed the Findings whose current proposal the Cyber Manager confirmed
 * @param awaitingReview the Findings whose current proposal awaits the Cyber Manager's review (review state PENDING)
 */
public record ReviewCounts(long confirmed, long awaitingReview) {

    /** A VA Submission with no Finding proposed, or none at all. */
    public static final ReviewCounts NONE = new ReviewCounts(0, 0);
}
