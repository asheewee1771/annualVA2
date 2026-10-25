package sg.annualva.register;

/**
 * A System as the Register has it ({@code before}) or as the file would make it ({@code after}), for the preview.
 * Emails are lower-case; {@code quarter} is {@code Q1} to {@code Q4}, or, in the {@code after} of a row whose Quarter
 * is wrong, what was typed (clipped). {@code retired} is true for a System the Register already holds as retired.
 */
public record SystemSnapshot(String name, String smoEmail, String cyberManagerEmail, String quarter, boolean retired) {

    /** Whether {@code after} puts the System in another Quarter: a move. */
    boolean movesIn(SystemSnapshot after) {
        return !quarter.equals(after.quarter());
    }

    /** Whether {@code after} gives the System another SMO/Tech Lead. */
    boolean changesSmoIn(SystemSnapshot after) {
        return !smoEmail.equals(after.smoEmail());
    }

    /** Whether {@code after} gives the System another SMO/Tech Lead or Cyber Manager: a transfer (spec, Modules). */
    boolean transfersIn(SystemSnapshot after) {
        return changesSmoIn(after) || !cyberManagerEmail.equals(after.cyberManagerEmail());
    }
}
