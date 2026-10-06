package sg.annualva.register;

import java.util.Locale;
import java.util.UUID;

/** A System as the Register holds it today. */
record ExistingSystem(UUID id, String code, String name, String smoEmail, String cyberManagerEmail, int quarter, boolean retired) {

    SystemSnapshot snapshot() {
        return new SystemSnapshot(name, smoEmail.toLowerCase(Locale.ROOT), cyberManagerEmail.toLowerCase(Locale.ROOT),
                "Q" + quarter, retired);
    }
}
