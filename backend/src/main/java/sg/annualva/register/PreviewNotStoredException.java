package sg.annualva.register;

/**
 * A preview could not be stored. It deliberately has no cause and no stack trace: the database driver's own message
 * can quote the document it was given, which holds the uploaded file's cells, and report content never appears in a
 * log (lm-19). The caller gets the generic internal error.
 */
final class PreviewNotStoredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PreviewNotStoredException() {
        super("The Register preview could not be stored", null, false, false);
    }
}
