package sg.annualva.register;

/** A Register file that cannot become a preview. Carries a reason and, for a wrong header, a column: never content. */
final class RegisterFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UploadRejection reason;
    private final int column;

    RegisterFileException(UploadRejection reason) {
        this(reason, 0);
    }

    /** @param column 1 for A; 0 when no column is meant */
    RegisterFileException(UploadRejection reason, int column) {
        super(reason.name(), null, false, false);
        this.reason = reason;
        this.column = column;
    }

    UploadRejection reason() {
        return reason;
    }

    int column() {
        return column;
    }
}
