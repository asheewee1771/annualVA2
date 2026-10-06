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
 * @param reasons why the row is BLOCKED, as fixed codes; empty for every other outcome
 * @param unlinkedSmo the SMO/Tech Lead's email matches no sign-in yet (Unlinked SMO, story 12)
 * @param before the System as the Register has it now; null when there is none
 * @param after the System as the file would make it, or as typed (sanitised) for a BLOCKED row; null for a RETIRE
 */
public record PreviewRow(
        @Nullable Integer sheetRow,
        @Nullable String systemId,
        @Nullable UUID existingSystemId,
        Outcome outcome,
        List<BlockReason> reasons,
        boolean unlinkedSmo,
        @Nullable SystemSnapshot before,
        @Nullable SystemSnapshot after) {}
