package sg.annualva.retention;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One item of a disposal list: ids and codes only.
 *
 * @param targetId the VA Submission, file, export or System; null for a Cycle or the audit range
 * @param systemId the System the item is about, when there is one
 * @param cycle the VA Cycle whose retention ended, or the superseded file's VA Cycle; null for an export or a System
 * @param entries for the audit range, how many entries were before the cutoff; null otherwise
 */
record DisposalItem(
        ItemKind kind, @Nullable UUID targetId, @Nullable UUID systemId, @Nullable Integer cycle, DisposalReason reason,
        @Nullable Long entries) {

    /** The item as one line, for the list's fingerprint. */
    String line() {
        return kind + "|" + targetId + "|" + systemId + "|" + cycle + "|" + reason + "|" + entries;
    }
}
