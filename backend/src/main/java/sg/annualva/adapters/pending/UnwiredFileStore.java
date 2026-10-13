package sg.annualva.adapters.pending;

import org.springframework.stereotype.Component;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore;

/**
 * The file store until the production adapter exists (ticket 51 builds the S3 store with SSE-KMS). It fails closed: every
 * call says the store is not wired, so an upload is refused rather than kept somewhere unprotected, and the application
 * still starts under the {@code dev-mcc} profile and in production. The Seam 1 harness replaces it with a fake.
 */
@Component
class UnwiredFileStore implements FileStore {

    private static final String NOT_WIRED = "The file store is not wired";

    @Override
    public void put(Zone zone, String key, byte[] content) {
        throw new BoundaryFailureException(NOT_WIRED);
    }

    @Override
    public byte[] get(Zone zone, String key) {
        throw new BoundaryFailureException(NOT_WIRED);
    }

    @Override
    public void delete(Zone zone, String key) {
        throw new BoundaryFailureException(NOT_WIRED);
    }
}
