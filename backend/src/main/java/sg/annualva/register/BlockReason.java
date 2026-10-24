package sg.annualva.register;

/**
 * Why a Register row cannot be applied. A fixed code: the preview says what is wrong with a row and never repeats what
 * the cell held (the person can see their own file). The front end gives each code its sentence.
 */
public enum BlockReason {
    SYSTEM_ID_MISSING,
    SYSTEM_ID_INVALID,
    SYSTEM_ID_DUPLICATE,
    /** The System ID starts {@code EXAMPLE-}: the template's sample row, which must not become a real System (ADR 0016). */
    SYSTEM_ID_EXAMPLE,
    SYSTEM_NAME_MISSING,
    SYSTEM_NAME_INVALID,
    SMO_EMAIL_MISSING,
    SMO_EMAIL_INVALID,
    /** Story 14: every System has an independent reviewer. */
    CYBER_MANAGER_MISSING,
    CYBER_MANAGER_EMAIL_INVALID,
    /** Story 14: the System's own SMO/Tech Lead may not be its Cyber Manager. */
    CYBER_MANAGER_IS_SMO,
    QUARTER_MISSING,
    QUARTER_INVALID,
    /**
     * The retired System's in-flight VA Submission has a file still being checked, a rescan included: it cannot leave its
     * state until the file settles (decision 2026-10-18, ADR 0048).
     */
    FILES_SCANNING,
    /** A move of a System with in-flight work in a VA Cycle the move changes: Release 2's mid-cycle tickets (ADR 0048). */
    MOVE_IN_FLIGHT,
    /** A transfer of a System with in-flight work to another SMO/Tech Lead or Cyber Manager: Release 2 (ADR 0048). */
    TRANSFER_IN_FLIGHT,
    /**
     * The change would give one person two steps of an in-flight VA Submission: its new SMO/Tech Lead or Cyber Manager
     * acted at, or was assigned to, another step of it (story 58, ADR 0048).
     */
    SEGREGATION_OF_DUTIES
}
