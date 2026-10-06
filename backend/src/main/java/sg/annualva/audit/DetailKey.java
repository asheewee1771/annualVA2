package sg.annualva.audit;

import java.util.Locale;

/**
 * The only keys an audit entry's {@code detail} may carry. The same list is a CHECK constraint on the table
 * ({@code audit_entry_detail_check}), so adding a key is a deliberate change to both. Each value is a fixed
 * code, an id or a number, never text a person typed or anything read from a report.
 */
public enum DetailKey {
    STATE_FROM,
    STATE_TO,
    BATCH_ID,
    COUNT,
    FILE_ID,
    REJECT_CODE,
    /** Why a request or sign-in was refused: a fixed code such as {@code mfa-not-evidenced}. */
    REASON,
    /** The sensitive action a step-up was for: a lower-case code such as {@code register-apply}. */
    INTENT,
    /** The HTTP status of a refused request. */
    STATUS,
    /** The OAuth 2.0 error code of a failed sign-in, from a fixed list. */
    ERROR_CODE,
    ROLE,
    SOURCE,
    GRANT_ID,
    /** The Register change preview an entry is about. */
    PREVIEW_ID,
    /** What a Register apply did, as counts: Systems added, updated, retired and left as they were. */
    ADDED,
    UPDATED,
    RETIRED,
    UNCHANGED,
    /** The role grants a Register apply gave and revoked. */
    ROLES_GRANTED,
    ROLES_REVOKED;

    /** The key as stored: lower-case with underscores. */
    String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
