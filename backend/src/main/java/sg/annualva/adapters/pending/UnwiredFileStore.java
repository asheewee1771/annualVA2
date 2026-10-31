package sg.annualva.adapters.pending;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import sg.annualva.config.NotDevLocal;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore;

/**
 * The file store wherever the S3 store is not switched on ({@code annualva.aws.enabled}, which {@code dev-mcc} sets; ticket
 * 51, ADR 0059). It fails closed: every call says the store is not wired, so an upload is refused rather than kept
 * somewhere unprotected, and the application still starts in a deployed profile that has not been given a bucket. It and
 * the S3 store are never both present, so there is one {@code FileStore} bean. The Seam 1 harness replaces it with a fake.
 */
@Component
@NotDevLocal
@ConditionalOnProperty(prefix = "annualva.aws", name = "enabled", havingValue = "false", matchIfMissing = true)
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
