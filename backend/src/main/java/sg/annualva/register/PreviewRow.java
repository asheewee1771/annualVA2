package sg.annualva.register;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One line of a Register change preview, and the shape in which it is stored and returned. Its position in the list
 * is the order it is shown in: the file's rows in sheet order, then the Systems the file leaves out.
 *
 * @param sheetRow the row in the spreadsheet, or null for a System the file leaves out
 * @param systemId the System ID as typed (trimmed, sanitised, clipped), or null when the cell was empty; for a System
 *     the file leaves out, the Register's own
 * @param existingSystemId the Register's id of the System this row matches, or null when there is none (an ADD, or a
 *     row whose System ID cannot be used)
 * @param outcome the one thing this row does
 * @param reasons why the row cannot be applied, as fixed codes: for a BLOCKED row what is wrong in the file; for a RETIRE
 *     or an UPDATE what its in-flight work holds it back by (ADR 0048); empty otherwise
 * @param unlinkedSmo the SMO/Tech Lead's email matches no sign-in yet (Unlinked SMO, story 12)
 * @param before the System as the Register has it now; null when there is none
 * @param after the System as the file would make it, or as typed (sanitised) for a BLOCKED row; null for a RETIRE
 * @param effects what the row does to the System's in-flight work (story 5, ADR 0048); empty when it touches none
 * @param inFlight the in-flight VA Submissions those effects are on; empty when there are no effects
 */
public record PreviewRow(
        @Nullable Integer sheetRow,
        @Nullable String systemId,
        @Nullable UUID existingSystemId,
        Outcome outcome,
        List<BlockReason> reasons,
        boolean unlinkedSmo,
        @Nullable SystemSnapshot before,
        @Nullable SystemSnapshot after,
        List<Effect> effects,
        List<InFlight> inFlight) {

    /** A preview stored before ticket 43 has no effects (null when read): it is read as touching no in-flight work. */
    public PreviewRow {
        reasons = List.copyOf(reasons);
        effects = effects == null ? List.of() : List.copyOf(effects);
        inFlight = inFlight == null ? List.of() : List.copyOf(inFlight);
    }

    /** A row as the file and the Register alone make it, before its effects on in-flight work are known. */
    PreviewRow(@Nullable Integer sheetRow, @Nullable String systemId, @Nullable UUID existingSystemId, Outcome outcome,
            List<BlockReason> reasons, boolean unlinkedSmo, @Nullable SystemSnapshot before,
            @Nullable SystemSnapshot after) {
        this(sheetRow, systemId, existingSystemId, outcome, reasons, unlinkedSmo, before, after, List.of(), List.of());
    }

    /**
     * An in-flight VA Submission a row's effects are on, as the preview shows it.
     *
     * @param cycle its VA Cycle's year
     * @param state its state when the preview was made
     */
    public record InFlight(UUID submissionId, int cycle, String state) {}
}
