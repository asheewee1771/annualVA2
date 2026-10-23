package sg.annualva.ingestion;

import java.util.Locale;
import sg.annualva.parser.ParsedReport;

/**
 * Why the pipeline rejected a file. Each code has one fixed sentence for the SMO/Tech Lead, written here and never built
 * from the file (as-13, lm-19), and one fixed audit code. A rejected file keeps no bytes.
 */
public enum RejectCode {
    /** The scanner found malware (story 24). */
    MALWARE("malware",
            "The malware scan blocked this file, so the upload was rejected. Check the file and upload a clean copy."),
    /** The scanner passed the file only after changing it; the altered copy is not accepted (reviewer-decisions.md). */
    SANITIZED("sanitized",
            "The malware scan had to modify this file, so the upload was rejected. Upload the original file again."),
    /** Three scan attempts timed out (story 27). */
    SCAN_TIMEOUT("scan-timeout",
            "The malware scan did not finish in time, so the upload was rejected. Upload the file again."),
    /** Not an accepted type for this Scan Report. */
    TYPE("type", "This file is not an accepted type for this Scan Report, so the upload was rejected."),
    /** Larger than the limit for its type. */
    TOO_LARGE("too-large", "This file is larger than the limit for its type, so the upload was rejected."),
    /**
     * Within the size limit, but holding more than one file may: more Findings or places than ParsedReport.MAX_FINDINGS and
     * MAX_OCCURRENCES, or one item with far more entries than a real report has.
     */
    TOO_MANY_FINDINGS("too-many-findings",
            "This file holds more than one file can: more than "
                    + String.format(Locale.ROOT, "%,d", ParsedReport.MAX_FINDINGS) + " findings, more than "
                    + String.format(Locale.ROOT, "%,d", ParsedReport.MAX_OCCURRENCES)
                    + " places they were found, or a single item with far more entries than a real report has, so the "
                    + "upload was rejected. Export the scan in smaller parts and upload each part."),
    /** Cannot be read to the end. */
    UNREADABLE("unreadable", "This file could not be read, so the upload was rejected."),
    /** Readable but not built the way this Scan Report needs. */
    SCHEMA("schema", "This file does not have the structure this Scan Report needs, so the upload was rejected."),
    /** The scanner called the file unchanged but returned different bytes. */
    HASH_MISMATCH("hash-mismatch",
            "The scan result did not match the file that was uploaded, so the upload was rejected. Upload the file again."),
    /** A PDF that asks for a password to open: the scanner cannot inspect it, so it is not kept (ADR 0026, reviewer-decisions.md). */
    ENCRYPTED("encrypted",
            "This PDF is password protected, so the upload was rejected. Upload a copy without a password."),
    /**
     * The report says the scan was made after today's business date (story 26): the age check, the pipeline's last stage. A
     * file whose report names no date is not refused for it.
     */
    FUTURE_DATED("future-dated",
            "This file's scan date is after today, so the upload was rejected. Check the clock of the machine that made "
            + "the scan, then export the report again.");

    private final String auditCode;
    private final String message;

    RejectCode(String auditCode, String message) {
        this.auditCode = auditCode;
        this.message = message;
    }

    /** The fixed code an audit entry records. */
    String auditCode() {
        return auditCode;
    }

    /** The plain message shown to the person; the same sentence every time. */
    public String message() {
        return message;
    }
}
