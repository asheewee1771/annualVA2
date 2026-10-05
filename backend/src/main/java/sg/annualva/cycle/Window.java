package sg.annualva.cycle;

/** The three windows of a Quarter; they are soft, nothing locks when one is missed. */
public enum Window {
    /** Weeks 1 and 2: the SMO/Tech Lead submits. */
    SUBMISSION,
    /** Weeks 3 and 4: the Cyber Manager reviews. */
    REVIEW,
    /** Week 5 to the Quarter's last day: a VA POC endorses. */
    ENDORSEMENT
}
