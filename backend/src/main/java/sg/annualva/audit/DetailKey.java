package sg.annualva.audit;

import java.util.Locale;

/**
 * The only keys an audit entry's {@code detail} may carry. The same list is a CHECK constraint on the table
 * ({@code audit_entry_detail_check}), so adding a key is a deliberate change to both. Each value is a fixed
 * code, an id, a number or a yes/no flag, never text a person typed or anything read from a report.
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
    ROLES_REVOKED,
    /** The Finding Status a proposal was for: one of four fixed codes such as {@code RISK_ACCEPTED}. */
    FINDING_STATUS,
    /** The Scan Report file a rescan replaced (ticket 21); {@link #FILE_ID} is the rescan. */
    REPLACED_FILE_ID,
    /** Who a proposal was made as: a fixed code such as {@code RESCAN} (ticket 21). */
    PROPOSED_AS,
    /** The rescan whose settlement made a proposal (ticket 21); {@link #FILE_ID} on a proposal is its approval memo. */
    RESCAN_FILE_ID,
    /** The Scan Report an entry is about: a fixed code such as {@code BURP_SUITE} (ticket 22). */
    REPORT_TYPE,
    /** How a Scan Report is provided: {@code UPLOAD}, {@code NOT_APPLICABLE} or {@code MANUAL} (ticket 22). */
    MODE,
    /** Whether a confirmed scan date differs from the report file's own day (ticket 23): a flag, never the date. */
    DATE_CHANGED,
    /** Whether a confirmed target differs from the one the report file names (ticket 23): a flag, never the target. */
    TARGET_CHANGED,
    /** The Declaration version a submit accepted (ticket 25): a number, never the wording. */
    DECLARATION_VERSION,
    /**
     * The state a VA Submission was in when something happened to it that is not a move (ticket 26: the Overdue sweep),
     * or {@code NOT_STARTED} for a System with none: a fixed code.
     */
    STATE,
    /** What a review decided (ticket 28): {@code CONFIRMED} or {@code DISPUTED}, never the comment. */
    DECISION,
    /** Whether an endorsement came after the Quarter's last day (ticket 34): a flag, never a date. */
    ENDORSED_LATE,
    /** The VA Cycle an entry is about (tickets 41 and 44): its year, never a date. */
    CYCLE,
    /** The export an entry is about (ticket 44). */
    EXPORT_ID,
    /** What an export is: a fixed code such as {@code MGMT_SUMMARY}. */
    EXPORT_TYPE,
    /** The file an export is made as: a fixed code such as {@code XLSX}. */
    FORMAT,
    /** An export's classification: {@code CONFIDENTIAL} or {@code RESTRICTED} (assumption A-1). */
    CLASSIFICATION,
    /** The Quarter an export covers, 1 to 4, when it covers one Quarter only. */
    QUARTER;

    /** The key as stored: lower-case with underscores. */
    String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
