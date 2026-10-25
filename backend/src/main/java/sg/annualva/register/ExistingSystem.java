package sg.annualva.register;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A System as the Register holds it today.
 *
 * @param smoUnreachableAt when the mail relay first refused its SMO/Tech Lead's address since a mail to it last went
 *     through; null while it is not flagged SMO Unreachable (ADR 0051)
 */
record ExistingSystem(UUID id, String code, String name, String smoEmail, String cyberManagerEmail, int quarter, boolean retired,
        @Nullable Instant smoUnreachableAt) {

    SystemSnapshot snapshot() {
        return new SystemSnapshot(name, smoEmail.toLowerCase(Locale.ROOT), cyberManagerEmail.toLowerCase(Locale.ROOT),
                "Q" + quarter, retired);
    }
}
