package sg.annualva.finding;

/**
 * How far the Findings of a VA Submission are (story 38): what the progress bar shows. Counts only, never content.
 *
 * @param total every Finding of the VA Submission
 * @param proposed the Findings that have a proposed Finding Status, whatever its review
 * @param unproposed the Findings with no proposed Finding Status yet: what the SMO/Tech Lead still has to propose
 * @param awaitingReview the Findings whose current proposal awaits the Cyber Manager's review (review state PENDING)
 * @param carriedOver the Carried Over Findings still to be confirmed again; none until carry-over arrives (Release 2)
 * @param targetDateMissed the Target Date Missed Findings; none until that rule arrives (Release 2)
 * @param disputed the Findings whose current proposal the Cyber Manager Disputed
 */
public record Readiness(
        long total, long proposed, long unproposed, long awaitingReview, long carriedOver, long targetDateMissed,
        long disputed) {}
