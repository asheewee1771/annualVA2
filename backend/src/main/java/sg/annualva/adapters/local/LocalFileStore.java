package sg.annualva.adapters.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import sg.annualva.config.DevLocal;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore;

/**
 * The file store of the {@code dev-local} profile: each zone is a sub-folder of one local folder, so files outlive a
 * restart, and an uploaded file is never kept anywhere else. The key is the application's random id; a key that could
 * name a place outside the zone folder is refused, and the refusal says nothing about the key. Nothing is encrypted:
 * this is for one developer's machine, with mock data.
 */
@Component
@DevLocal
class LocalFileStore implements FileStore {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;

    @Autowired
    LocalFileStore(LocalAdapterProperties properties) {
        this(properties.fileStoreDir());
    }

    LocalFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(Zone zone, String key, byte[] content) {
        Path target = path(zone, key);
        Path temporary = target.resolveSibling("." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            discard(temporary);
            throw new BoundaryFailureException("The local file store could not write the file");
        }
    }

    @Override
    public byte[] get(Zone zone, String key) {
        try {
            return Files.readAllBytes(path(zone, key));
        } catch (NoSuchFileException e) {
            throw new BoundaryFailureException("The local file store holds nothing under this key");
        } catch (IOException e) {
            throw new BoundaryFailureException("The local file store could not read the file");
        }
    }

    @Override
    public void delete(Zone zone, String key) {
        try {
            Files.deleteIfExists(path(zone, key));
        } catch (IOException e) {
            throw new BoundaryFailureException("The local file store could not delete the file");
        }
    }

    /** Whether a key is one this store keeps: the application's random ids, never a path. */
    static boolean acceptsKey(String key) {
        return KEY.matcher(key).matches();
    }

    private Path path(Zone zone, String key) {
        if (!acceptsKey(key)) {
            throw new BoundaryFailureException("The local file store does not accept this key");
        }
        return root.resolve(zone.name().toLowerCase(Locale.ROOT)).resolve(key);
    }

    private static void discard(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // A leftover temporary file in a developer's local folder is harmless.
        }
    }
}
