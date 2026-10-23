package sg.annualva.submission;

/**
 * Where a VA Submission is in its life. A System with no VA Submission is Not Started and has no state of its own.
 * Each state is added with the command that enters it or leaves it; the table accepts all nine states a row can be in
 * (with Not Started, the spec's ten) from the start, so a later ticket adds a constant and not a migration.
 */
public enum SubmissionState {
    /** Started by the SMO/Tech Lead, not yet submitted. */
    DRAFT,
    /** Submitted by the SMO/Tech Lead; with the Cyber Manager. */
    IN_REVIEW,
    /**
     * Back with the SMO/Tech Lead, because of Disputed Findings or a VA POC return; submitted again like a Draft. The
     * commands that return a VA Submission arrive with tickets 30 and 35.
     */
    RETURNED_FOR_REWORK
}
