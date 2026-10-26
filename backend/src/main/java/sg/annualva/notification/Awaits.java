package sg.annualva.notification;

/**
 * The VA POC decision that handles an item both VA POCs received (story 69): when either makes it, the item clears for
 * both, marked with who handled it. A new kind is added here and to its migration's {@code notification_awaits_check}.
 */
public enum Awaits {
    /** Endorsing a VA Submission Awaiting Endorsement, or returning it for rework. */
    ENDORSEMENT,
    /** Approving or rejecting an Exemption request (ticket 40). */
    EXEMPTION,
    /** Confirming the VA an SMO/Tech Lead recorded for a conditional Exemption (ticket 42). */
    VA_CONFIRMATION,
    /**
     * Giving a System someone who can act at a step again, once its SMO/Tech Lead's or Cyber Manager's role is suspended
     * or lapsed (decided 2026-10-26): restoring the role, or applying a Register that names someone else.
     */
    ROLE_HOLDER
}
