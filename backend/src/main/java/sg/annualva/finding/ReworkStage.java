package sg.annualva.finding;

/**
 * The stages of the rework queue (story 60), in the order the SMO/Tech Lead works through them. Release 2 puts Target Date
 * Missed and Carried Over (to re-confirm) between the Disputed Findings and the new ones.
 */
public enum ReworkStage {
    /** The Cyber Manager Disputed the current proposal. */
    DISPUTED,
    /** A Critical or High Finding with no proposal yet. */
    NEW_CRITICAL_HIGH,
    /** A Medium, Low or Informational Finding with no proposal yet. */
    NEW_MEDIUM_LOW
}
