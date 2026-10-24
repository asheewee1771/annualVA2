package sg.annualva.boundary;

/**
 * The file-store port: the encrypted object store that holds uploaded files and the exports the application makes. An
 * uploaded file never moves between the first two zones without a scan: bytes arrive in {@link Zone#QUARANTINE}, which
 * nobody can read through the application, and only bytes the malware scanner passed are put in {@link Zone#CLEAN}. A
 * file the application made itself for someone to download (an export) is kept apart, in {@link Zone#EXPORT}. A key is an opaque name chosen by the
 * application (a random id); it never contains a file name. The production adapter is S3 with SSE-KMS (ticket 51); the
 * Seam 1 harness fakes it with an in-memory store.
 */
public interface FileStore {

    /** Where a file is kept: unscanned (quarantine), passed by the scanner (clean), or made by the application (export). */
    enum Zone {
        QUARANTINE,
        CLEAN,
        EXPORT
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
