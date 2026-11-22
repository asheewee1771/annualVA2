package sg.annualva.harness;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore;

/**
 * The file-store fake: the three zones held in memory. A test can ask what each zone holds (the quarantine zone must be empty
 * once a file is clean or rejected) and make the store refuse writes or reads, as an object store that cannot be reached.
 */
public final class InMemoryFileStore implements FileStore {

    private final Map<String, byte[]> quarantine = new ConcurrentHashMap<>();
    private final Map<String, byte[]> clean = new ConcurrentHashMap<>();
    private final Map<String, byte[]> exports = new ConcurrentHashMap<>();
    private final AtomicBoolean refusingWrites = new AtomicBoolean();
    private final AtomicBoolean refusingReads = new AtomicBoolean();
    private final AtomicBoolean refusingDeletes = new AtomicBoolean();
    private volatile Runnable beforeQuarantine;

    @Override
    public void put(Zone zone, String key, byte[] content) {
        Runnable hook = beforeQuarantine;
        if (zone == Zone.QUARANTINE && hook != null) {
            beforeQuarantine = null;
            hook.run();
        }
        if (refusingWrites.get()) {
            throw new BoundaryFailureException("The fake store refuses writes");
        }
        zone(zone).put(key, content.clone());
    }

    @Override
    public byte[] get(Zone zone, String key) {
        byte[] content = zone(zone).get(key);
        if (refusingReads.get() || content == null) {
            throw new BoundaryFailureException("The fake store cannot read this");
        }
        return content.clone();
    }

    @Override
    public void delete(Zone zone, String key) {
        if (refusingDeletes.get()) {
            throw new BoundaryFailureException("The fake store refuses deletes");
        }
        zone(zone).remove(key);
    }

    /** How many files the zone holds. */
    public int count(Zone zone) {
        return zone(zone).size();
    }

    /** The keys the zone holds. */
    public Set<String> keys(Zone zone) {
        return Set.copyOf(zone(zone).keySet());
    }

    /** The bytes held under a key, without the failure a read can be made to have. */
    public byte[] peek(Zone zone, String key) {
        return zone(zone).get(key);
    }

    /**
     * Runs once, just before the next upload's bytes are quarantined: after the request's first checks, before the
     * transaction that keeps the file, so a test can change the world in between.
     */
    public void beforeNextQuarantine(Runnable hook) {
        beforeQuarantine = hook;
    }

    public void refuseWrites(boolean refuse) {
        refusingWrites.set(refuse);
    }

    public void refuseReads(boolean refuse) {
        refusingReads.set(refuse);
    }

    public void refuseDeletes(boolean refuse) {
        refusingDeletes.set(refuse);
    }

    /** Back to an empty store that works. */
    public void reset() {
        quarantine.clear();
        clean.clear();
        exports.clear();
        refusingWrites.set(false);
        refusingReads.set(false);
        refusingDeletes.set(false);
        beforeQuarantine = null;
    }

    private Map<String, byte[]> zone(Zone zone) {
        return switch (zone) {
            case QUARANTINE -> quarantine;
            case CLEAN -> clean;
            case EXPORT -> exports;
        };
    }
}
