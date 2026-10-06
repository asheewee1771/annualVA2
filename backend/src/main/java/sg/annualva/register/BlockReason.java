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
    QUARTER_INVALID
}
