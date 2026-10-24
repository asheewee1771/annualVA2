package sg.annualva.submission;

/** The steps of a VA Submission at which a person acts; segregation of duties allows one person only one of them. */
public enum SubmissionStep {
    /** Starting and submitting: the SMO/Tech Lead. */
    SUBMIT,
    /**
     * Confirming or Disputing Findings, returning and passing to endorsement: the Cyber Manager. The review engine (ticket
     * 28) records it; the Cyber Manager's inbox already asks who acted at any other step.
     */
    REVIEW
}
