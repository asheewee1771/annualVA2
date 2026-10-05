package sg.annualva.web;

/**
 * "There is no such resource", for the caller. Thrown both when a resource does not exist and when
 * it exists but the caller has no scope over it, so the two are one and the same answer (IM8 ac-1:
 * nobody learns which Systems or VA Submissions exist). It carries no message and no identifier.
 */
public final class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException() {
        super(null, null, false, false);
    }
}
