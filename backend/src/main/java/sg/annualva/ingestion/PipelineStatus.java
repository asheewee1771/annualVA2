package sg.annualva.ingestion;

/**
 * Where a Scan Report file is in the upload pipeline. The stages run in a fixed order: quarantine, malware scan, type
 * allowlist, schema check, parse (which also pre-fills the scan date and target), then the age check, which refuses a scan
 * dated after today and rests nowhere of its own: a file that passes it is PARSED. A supporting evidence file skips the
 * last three: quarantine, scan, type allowlist, READY.
 * Nothing reads a file before it is clean, and nobody can view one until it has passed every stage there is. A file is
 * stored in the statuses it rests in (QUARANTINED, SCAN_SUBMITTED, PARSED, READY and the rejected ones); SCAN_CLEAN,
 * TYPE_OK and SCHEMA_OK are stages it passes through, recorded in its stage history when the outcome of the checks is
 * written in one step.
 */
public enum PipelineStatus {
    /** Held in quarantine, not yet with the scanner. */
    QUARANTINED,
    /** With the scanner, which has not answered. */
    SCAN_SUBMITTED,
    /** The scanner passed it and its clean copy is stored. */
    SCAN_CLEAN,
    /** Its bytes are of a type this Scan Report accepts, within the size limit. */
    TYPE_OK,
    /** It is readable and has the structure the type needs; a file passes through this on its way to PARSED, as every format has a parser. */
    SCHEMA_OK,
    /** A parser read its Findings and the scan date and target it names; they are stored with it. */
    PARSED,
    /**
     * A supporting evidence file (a PDF or HTML report) that passed the scan and the type check. It is never schema-checked
     * or parsed, so it has no SCHEMA_OK or PARSED stage: this is where it rests, ready to be downloaded (ADR 0026).
     */
    READY,
    REJECTED_MALWARE,
    REJECTED_SANITIZED,
    REJECTED_SCAN_TIMEOUT,
    REJECTED_TYPE,
    REJECTED_TOO_LARGE,
    /** It parsed, but would make more Findings or places than one file may hold (ADR 0023). */
    REJECTED_TOO_MANY_FINDINGS,
    REJECTED_UNREADABLE,
    REJECTED_SCHEMA,
    REJECTED_HASH_MISMATCH,
    /** A PDF that is password protected, which the scanner cannot inspect (supporting evidence only, ADR 0026). */
    REJECTED_ENCRYPTED,
    /** It parsed, but its report says the scan was made after today's business date: the age check (ADR 0031). */
    REJECTED_FUTURE_DATED;

    /** The status of a file the pipeline rejected for this reason. */
    static PipelineStatus rejected(RejectCode code) {
        return valueOf("REJECTED_" + code.name());
    }

    boolean rejected() {
        return name().startsWith("REJECTED_");
    }

    /** Only a file that passed every stage there is for its format can be viewed or downloaded. */
    boolean viewable() {
        return this == SCHEMA_OK || this == PARSED || this == READY;
    }
}
