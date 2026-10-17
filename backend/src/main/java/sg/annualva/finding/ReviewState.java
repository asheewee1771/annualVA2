package sg.annualva.finding;

/**
 * Where the Cyber Manager's review of a Finding's current proposal is. A Finding with no proposal has no review state;
 * a proposal starts {@link #PENDING}, and a new one puts it back there. Confirming and Disputing arrive with the review
 * engine (tickets 28 and after).
 */
public enum ReviewState {
    /** Proposed, not yet confirmed or Disputed. */
    PENDING,
    /** The Cyber Manager confirmed the current proposal. */
    CONFIRMED,
    /** The Cyber Manager does not accept the current proposal. */
    DISPUTED
}
