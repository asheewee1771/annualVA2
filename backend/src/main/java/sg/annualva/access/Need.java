package sg.annualva.access;

/**
 * What a caller wants to do with a System, named after the steps of the VA Submission flow. Each
 * need maps to the roles that hold it: who may view, who submits, who reviews, who endorses and who reads the audit
 * history.
 */
public enum Need {
    /** Read the System's reports and Findings: its SMO/Tech Lead, its Cyber Manager, any VA POC. */
    VIEW,
    /** Prepare and submit: the SMO/Tech Lead named for the System. */
    SUBMIT,
    /** Confirm or Dispute Findings, return, pass on: the Cyber Manager assigned to the System. */
    REVIEW,
    /** Endorse, return or decide an Exemption: a VA POC, for any System. */
    ENDORSE,
    /**
     * Read the audit history of the System's VA Submissions: any VA POC (every entry), and the Cyber Manager assigned to
     * it (the VA Submissions they reviewed, ADR 0038).
     */
    AUDIT
}
