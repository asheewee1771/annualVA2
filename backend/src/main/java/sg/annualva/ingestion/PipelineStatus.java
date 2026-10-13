package sg.annualva.ingestion;

/**
 * Where a Scan Report file is in the upload pipeline. The stages run in a fixed order: quarantine, malware scan, type
 * allowlist, schema check. The parser and metadata tickets append parse, pre-fill and age after them. Nothing reads a
 * file before it is clean, and nobody can view one until it has passed every stage there is. A file is stored in the
 * statuses it rests in (QUARANTINED, SCAN_SUBMITTED, SCHEMA_OK and the rejected ones); SCAN_CLEAN and TYPE_OK are
 * stages it passes through, recorded in its stage history when the outcome of the checks is written in one step.
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
    /** It is readable and has the structure the type needs. Machine-readable files wait here for their parser. */
    SCHEMA_OK,
    REJECTED_MALWARE,
    REJECTED_SANITIZED,
    REJECTED_SCAN_TIMEOUT,
    REJECTED_TYPE,
    REJECTED_TOO_LARGE,
    REJECTED_UNREADABLE,
    REJECTED_SCHEMA,
    REJECTED_HASH_MISMATCH;

    /** The status of a file the pipeline rejected for this reason. */
    static PipelineStatus rejected(RejectCode code) {
        return valueOf("REJECTED_" + code.name());
    }

    boolean rejected() {
        return name().startsWith("REJECTED_");
    }

    /** Only a file that passed every stage can be viewed or downloaded; later tickets add the statuses after it. */
    boolean viewable() {
        return this == SCHEMA_OK;
    }
}
