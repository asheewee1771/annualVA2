package sg.annualva.export;

/** The file an export is made as. The PDF (ticket 45) adds its own. */
public enum ExportFormat {
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

    private final String mediaType;
    private final String extension;

    ExportFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    String mediaType() {
        return mediaType;
    }

    String extension() {
        return extension;
    }
}
