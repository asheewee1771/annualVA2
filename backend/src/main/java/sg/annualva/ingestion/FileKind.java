package sg.annualva.ingestion;

/** What a file is for. */
public enum FileKind {
    /** A report in a machine-readable format, read for Findings by the parsers (tickets 14 to 17). */
    MACHINE_READABLE,
    /** A PDF or HTML report attached for people to read and never parsed (ticket 18). */
    EVIDENCE
}
