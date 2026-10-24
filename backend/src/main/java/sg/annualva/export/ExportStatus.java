package sg.annualva.export;

/** Where an export is: waiting for the export job, made and downloadable, failed, or past its 24 hours. */
public enum ExportStatus {
    QUEUED,
    READY,
    FAILED,
    EXPIRED
}
