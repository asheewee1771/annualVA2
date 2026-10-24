package sg.annualva.submission;

/**
 * Where a VA Submission is in its life. A System with no VA Submission is Not Started and has no state of its own. The
 * table accepts all nine states a row can be in (with Not Started, the spec's ten) from the start. Every state is named
 * here, so a read (a dashboard, the VA Submission itself) can show a VA Submission whatever state it is in; the commands
 * that enter the later ones arrive with their tickets, and until then nothing but a test puts a VA Submission there.
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
    RETURNED_FOR_REWORK,
    /** Every Finding is confirmed; with the VA POCs (ticket 30). */
    AWAITING_ENDORSEMENT,
    /** Final: a VA POC accepted it (ticket 34). */
    ENDORSED,
    /** Frozen while a VA POC decides its Exemption (ticket 40). */
    EXEMPTION_REQUESTED,
    /** Final: its Exemption was approved (ticket 40). */
    EXEMPTED,
    /** Final: its System was retired before endorsement (ticket 43). */
    WITHDRAWN,
    /** Final: neither Endorsed nor Exempted when its VA Cycle closed (ticket 41). */
    NOT_ENDORSED;

    /**
     * Endorsed, Exempted, Withdrawn and Not Endorsed are final: nothing more is done (spec state table). Every other state
     * becomes Not Endorsed when its VA Cycle closes, Exemption Requested included (ADR 0043).
     */
    public boolean isFinal() {
        return switch (this) {
            case ENDORSED, EXEMPTED, WITHDRAWN, NOT_ENDORSED -> true;
            case DRAFT, IN_REVIEW, RETURNED_FOR_REWORK, AWAITING_ENDORSEMENT, EXEMPTION_REQUESTED -> false;
        };
    }
}
