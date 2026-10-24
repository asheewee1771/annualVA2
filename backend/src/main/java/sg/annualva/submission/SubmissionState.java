package sg.annualva.submission;

/**
 * Where a VA Submission is in its life: the spec's ten states. A System with no VA Submission is Not Started; so is a VA
 * Submission an Exemption request created once the request is rejected (ticket 40, ADR 0048), which a start then takes
 * from there. Every state is named here, so a read (a dashboard, the VA Submission itself) can show a VA Submission
 * whatever state it is in; the commands that enter the later ones arrive with their tickets, and until then nothing but a
 * test puts a VA Submission there.
 */
public enum SubmissionState {
    /** Not started: created by an Exemption request that was then rejected; a start makes it a Draft. */
    NOT_STARTED("Not Started"),
    /** Started by the SMO/Tech Lead, not yet submitted. */
    DRAFT("Draft"),
    /** Submitted by the SMO/Tech Lead; with the Cyber Manager. */
    IN_REVIEW("In Review"),
    /**
     * Back with the SMO/Tech Lead, because of Disputed Findings or a VA POC return; submitted again like a Draft. The
     * commands that return a VA Submission arrive with tickets 30 and 35.
     */
    RETURNED_FOR_REWORK("Returned for Rework"),
    /** Every Finding is confirmed; with the VA POCs (ticket 30). */
    AWAITING_ENDORSEMENT("Awaiting Endorsement"),
    /** Final: a VA POC accepted it (ticket 34). */
    ENDORSED("Endorsed"),
    /** Frozen while a VA POC decides its Exemption (ticket 40). */
    EXEMPTION_REQUESTED("Exemption Requested"),
    /** Final: its Exemption was approved (ticket 40). */
    EXEMPTED("Exempted"),
    /** Final: its System was retired before endorsement (ticket 43). */
    WITHDRAWN("Withdrawn"),
    /** Final: neither Endorsed nor Exempted when its VA Cycle closed (ticket 41). */
    NOT_ENDORSED("Not Endorsed");

    private final String label;

    SubmissionState(String label) {
        this.label = label;
    }

    /** The state's name as the screens and a mail show it (CONTEXT.md; the React app's {@code STATE_LABELS}). */
    public String label() {
        return label;
    }

    /**
     * Endorsed, Exempted, Withdrawn and Not Endorsed are final: nothing more is done (spec state table). Every other state
     * becomes Not Endorsed when its VA Cycle closes, Exemption Requested included (ADR 0044).
     */
    public boolean isFinal() {
        return switch (this) {
            case ENDORSED, EXEMPTED, WITHDRAWN, NOT_ENDORSED -> true;
            case NOT_STARTED, DRAFT, IN_REVIEW, RETURNED_FOR_REWORK, AWAITING_ENDORSEMENT, EXEMPTION_REQUESTED -> false;
        };
    }
}
