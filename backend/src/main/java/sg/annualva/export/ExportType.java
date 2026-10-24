package sg.annualva.export;

/** What an export is. The Cyber Manager's detailed export and the auditor pack add their own. */
public enum ExportType {
    /** The VA POC's management summary (story 81): counts and a by-System table, no vulnerability detail. */
    MGMT_SUMMARY("management-summary");

    private final String fileName;

    ExportType(String fileName) {
        this.fileName = fileName;
    }

    /** How its file's name starts; the Cycle, the Quarter and the extension follow. */
    String fileName() {
        return fileName;
    }
}
