package sg.annualva.register;

import org.springframework.http.HttpStatus;
import sg.annualva.web.RejectedRequestException;

/**
 * Why a Register file was refused before any preview was made. Each reason is a fixed problem type, a fixed sentence
 * and a fixed audit code: none of them is ever built from the file (as-13, lm-19), so a refused file teaches an
 * attacker nothing about what the application read.
 */
enum UploadRejection {
    EMPTY(HttpStatus.BAD_REQUEST, "file-empty", "empty", "The file is empty."),
    TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "file-too-large", "too-large", "The file is larger than 2 MB."),
    NOT_XLSX(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-file-type", "not-xlsx",
            "Only an Excel .xlsx workbook is accepted."),
    UNREADABLE(HttpStatus.UNPROCESSABLE_CONTENT, "register-file-unreadable", "unreadable",
            "The workbook could not be read."),
    /** The malware scan blocked the file (IM8 as-12); it is never read. */
    MALWARE(HttpStatus.UNPROCESSABLE_CONTENT, "file-rejected-malware", "malware",
            "The malware scan blocked this file, so the upload was rejected. Check the file and upload a clean copy."),
    /** The scanner passed the file only after changing it; the altered copy is not accepted (reviewer-decisions.md). */
    SANITIZED(HttpStatus.UNPROCESSABLE_CONTENT, "file-modified-by-scan", "sanitized",
            "The malware scan had to modify this file, so the upload was rejected. Upload the original file again."),
    /** Three scan attempts ran out of time (story 27); the file is never read. */
    SCAN_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "scan-timeout", "scan-timeout",
            "The malware scan did not finish in time, so the upload was rejected. Upload the file again."),
    /** The scanner called the file unchanged but returned other bytes; the file is never read. */
    SCAN_MISMATCH(HttpStatus.SERVICE_UNAVAILABLE, "scan-mismatch", "scan-mismatch",
            "The scan result did not match the file that was uploaded, so the upload was rejected. Upload the file again."),
    NO_SHEET(HttpStatus.UNPROCESSABLE_CONTENT, "register-sheet-missing", "no-sheet",
            "The workbook has no System Register sheet."),
    HEADER_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "register-header-mismatch", "header-mismatch",
            "The first row must hold the five template headers in order, and nothing after them."),
    NO_ROWS(HttpStatus.UNPROCESSABLE_CONTENT, "register-no-rows", "no-rows",
            "The sheet has no System rows. An empty sheet would retire every System."),
    TOO_MANY_ROWS(HttpStatus.UNPROCESSABLE_CONTENT, "register-too-many-rows", "too-many-rows",
            "The sheet has more than " + RegisterSheetReader.MAX_ROWS + " System rows.");

    private final HttpStatus status;
    private final String type;
    private final String auditCode;
    private final String detail;

    UploadRejection(HttpStatus status, String type, String auditCode, String detail) {
        this.status = status;
        this.type = type;
        this.auditCode = auditCode;
        this.detail = detail;
    }

    /** The fixed code an audit entry records. */
    String auditCode() {
        return auditCode;
    }

    /** The problem the caller gets: {@code column} (1 for A) only for a header that is not the template's. */
    RejectedRequestException toProblem(int column) {
        if (this == HEADER_MISMATCH && column > 0) {
            return new RejectedRequestException(status, type,
                    "Column " + columnLetter(column) + " does not hold the template header, or the sheet has more than"
                            + " the five template columns.");
        }
        return new RejectedRequestException(status, type, detail);
    }

    private static String columnLetter(int column) {
        StringBuilder letters = new StringBuilder();
        for (int n = column; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letters.toString();
    }
}
