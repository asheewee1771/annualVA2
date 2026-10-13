package sg.annualva.scan;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.annualva.boundary.FileStore;

/**
 * The clean zone of the file store: the only place a file the scanner passed is kept, and the only bytes the application
 * ever serves back. A key is the file's own id, never a file name, so a copy whose row was never written (a failed
 * commit) is found again by the next attempt, which overwrites it, and by the sweep, which removes it.
 */
@Service
public class CleanFiles {

    private static final Logger log = LoggerFactory.getLogger(CleanFiles.class);

    private final FileStore files;

    CleanFiles(FileStore files) {
        this.files = files;
    }

    /**
     * Keeps the bytes under the file's id, replacing any copy a failed attempt left.
     *
     * @return the key the bytes are kept under
     * @throws RuntimeException when the store cannot be written
     */
    public String put(UUID fileId, byte[] content) {
        String key = fileId.toString();
        files.put(FileStore.Zone.CLEAN, key, content);
        return key;
    }

    /** @throws RuntimeException when the store cannot be read or holds nothing under the key */
    public byte[] get(String key) {
        return files.get(FileStore.Zone.CLEAN, key);
    }

    /**
     * Removes the bytes; removing what is not there is not an error.
     *
     * @return whether it is gone. A failure is logged without the key and is not thrown
     */
    public boolean discard(String key) {
        try {
            files.delete(FileStore.Zone.CLEAN, key);
            return true;
        } catch (RuntimeException e) {
            log.atWarn().setMessage("A clean file could not be discarded")
                    .addKeyValue("event.action", "clean-discard-failed").log();
            return false;
        }
    }
}
