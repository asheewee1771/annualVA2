package sg.annualva.notification;

/**
 * The VA POC decision that handles an item both VA POCs received (story 69): when either makes it, the item clears for
 * both, marked with who handled it. Ticket 40 adds the Exemption decision here and to its migration's
 * {@code notification_awaits_check}.
 */
public enum Awaits {
    /** Endorsing a VA Submission Awaiting Endorsement, or returning it for rework. */
    ENDORSEMENT
}
