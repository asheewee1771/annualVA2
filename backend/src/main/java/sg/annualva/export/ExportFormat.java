package sg.annualva.export;

/** The file an export is made as: an Excel workbook (ticket 44) or a PDF (ticket 45) of the same content. */
public enum ExportFormat {
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    PDF("application/pdf", "pdf");

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
