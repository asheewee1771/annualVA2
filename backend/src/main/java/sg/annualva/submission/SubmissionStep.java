package sg.annualva.submission;

import sg.annualva.access.Need;

/**
 * The steps of a VA Submission at which a person acts; segregation of duties allows one person only one of them. The
 * person assigned to a step of a System in the Register (its SMO/Tech Lead submits, its Cyber Manager reviews) counts as
 * acting at that step even before they do.
 */
public enum SubmissionStep {
    /** Starting and submitting: the SMO/Tech Lead. */
    SUBMIT(Need.SUBMIT),
    /** Confirming or Disputing Findings, returning and passing to endorsement: the Cyber Manager (ticket 28). */
    REVIEW(Need.REVIEW);

    private final Need need;

    SubmissionStep(Need need) {
        this.need = need;
    }

    /** Who is assigned to the step of a System: those the access guard gives this need over it. */
    Need need() {
        return need;
    }
}
