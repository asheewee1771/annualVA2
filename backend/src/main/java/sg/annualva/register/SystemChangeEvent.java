package sg.annualva.register;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Register apply changed something its SMO/Tech Lead is told about (story 94). Published inside the apply's
 * transaction; the notifications listen after the commit (ADR 0043), so an apply that rolled back is never heard of.
 *
 * @param change what changed
 * @param formerSmoEmail for a transfer, the SMO/Tech Lead the System was transferred from; null otherwise. It is never
 *     logged or audited (lm-19).
 * @param at when the apply ran (the clock bean's instant)
 */
public record SystemChangeEvent(UUID systemId, Change change, @Nullable String formerSmoEmail, Instant at) {

    /** The Register changes an SMO/Tech Lead is told about. A withdrawal joins them with ticket 43. */
    public enum Change {
        /** The System moved to another Quarter. */
        MOVED,
        /** The System has another SMO/Tech Lead. */
        TRANSFERRED
    }
}
