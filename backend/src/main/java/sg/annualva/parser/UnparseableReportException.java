package sg.annualva.parser;

/**
 * The file cannot be read into Findings. It carries no message from the file or the reader, which may quote the content:
 * the caller says why in a fixed sentence (as-13, lm-19). The {@link Reason} tells the two causes apart so the person is
 * told the true one.
 */
public final class UnparseableReportException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why a file was refused. */
    public enum Reason {
        /** The file is not in the shape its format has. */
        SHAPE,
        /**
         * The file is in the shape of its format but holds more than the application takes from one file: more Findings or
         * places than {@link ParsedReport#MAX_FINDINGS} and {@link ParsedReport#MAX_OCCURRENCES}, or one item with more
         * entries than a parser's per-item bound.
         */
        OVER_A_LIMIT
    }

    private final Reason reason;

    /** The file is not in the shape its format has. */
    public UnparseableReportException() {
        this(Reason.SHAPE);
    }

    private UnparseableReportException(Reason reason) {
        super("The report cannot be read into Findings", null, false, false);
        this.reason = reason;
    }

    /** The file is in the shape of its format but holds more than one file may. */
    public static UnparseableReportException overALimit() {
        return new UnparseableReportException(Reason.OVER_A_LIMIT);
    }

    public Reason reason() {
        return reason;
    }
}
