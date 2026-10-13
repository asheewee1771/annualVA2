package sg.annualva.parser;

/**
 * The file is not in the shape its format has. It carries no message from the file or the reader, which may quote the
 * content: the caller says why in a fixed sentence (as-13, lm-19).
 */
public final class UnparseableReportException extends Exception {

    private static final long serialVersionUID = 1L;

    public UnparseableReportException() {
        super("The report is not in the shape its format has", null, false, false);
    }
}
