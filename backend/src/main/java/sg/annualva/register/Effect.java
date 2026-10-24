package sg.annualva.register;

/**
 * What a Register change does to in-flight work (story 5, ADR 0049): a VA Submission of the System that is not in a final
 * state. A row with no in-flight work has no effect. In Release 1 only a withdrawal is applied, once the VA POC confirms
 * it; a move and a transfer are shown and blocked until the mid-cycle tickets of Release 2.
 */
public enum Effect {
    /** The System is retired: its in-flight VA Submission becomes Withdrawn (story 7). */
    WILL_WITHDRAW,
    /** The System moves to another Quarter of a VA Cycle in which it has in-flight work. */
    WILL_MOVE,
    /** The System gets another SMO/Tech Lead or Cyber Manager while it has in-flight work. */
    WILL_TRANSFER
}
