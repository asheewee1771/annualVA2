package sg.annualva.boundary;

/**
 * The file-store port: the encrypted object store that holds uploaded files. It has two zones and a file never moves
 * between them without a scan: bytes arrive in {@link Zone#QUARANTINE}, which nobody can read through the application,
 * and only bytes the malware scanner passed are put in {@link Zone#CLEAN}. A key is an opaque name chosen by the
 * application (a random id); it never contains a file name. The production adapter is S3 with SSE-KMS (ticket 51); the
 * Seam 1 harness fakes it with an in-memory store.
 */
public interface FileStore {

    /** Where a file is kept: unscanned (quarantine) or passed by the scanner (clean). */
    enum Zone {
        QUARANTINE,
        CLEAN
    }

    /** Stores the bytes under the key, replacing any earlier ones. */
    void put(Zone zone, String key, byte[] content);

    /**
     * Reads the bytes back.
     *
     * @throws BoundaryFailureException when the store cannot be reached or holds nothing under the key
     */
    byte[] get(Zone zone, String key);

    /** Removes the bytes; removing what is not there is not an error. */
    void delete(Zone zone, String key);
}
