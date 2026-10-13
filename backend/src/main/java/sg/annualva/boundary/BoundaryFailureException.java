package sg.annualva.boundary;

/**
 * A boundary adapter could not do what it was asked: the object store or the scanner was unreachable, refused the call
 * or answered something unusable. It carries a fixed sentence and no cause, stack trace or detail from the other side,
 * because what a remote service says may quote a file name or a presigned address (lm-19, as-13).
 */
public final class BoundaryFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BoundaryFailureException(String what) {
        super(what, null, false, false);
    }
}
