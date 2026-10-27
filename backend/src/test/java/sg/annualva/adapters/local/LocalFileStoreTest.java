package sg.annualva.adapters.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.FileStore.Zone;

/** Ticket 63: the local file store keeps each zone in a folder of its own, so a restart keeps the files. */
class LocalFileStoreTest {

    @TempDir
    Path root;

    @Test
    void keepsWhatItWasGivenAndReadsItBack() {
        var store = new LocalFileStore(root);
        String key = UUID.randomUUID().toString();

        store.put(Zone.CLEAN, key, new byte[] {1, 2, 3});

        assertThat(store.get(Zone.CLEAN, key)).containsExactly(1, 2, 3);
    }

    @Test
    void keepsEachZoneInASubFolderOfItsOwn() {
        var store = new LocalFileStore(root);
        store.put(Zone.QUARANTINE, "k1", new byte[] {1});
        store.put(Zone.CLEAN, "k1", new byte[] {2});
        store.put(Zone.EXPORT, "k1", new byte[] {3});

        assertThat(root.resolve("quarantine").resolve("k1")).hasBinaryContent(new byte[] {1});
        assertThat(root.resolve("clean").resolve("k1")).hasBinaryContent(new byte[] {2});
        assertThat(root.resolve("export").resolve("k1")).hasBinaryContent(new byte[] {3});
    }

    @Test
    void aNewStoreOnTheSameFolderSeesTheFilesOfTheOldOne() {
        new LocalFileStore(root).put(Zone.EXPORT, "kept", new byte[] {9});

        assertThat(new LocalFileStore(root).get(Zone.EXPORT, "kept")).containsExactly(9);
    }

    @Test
    void putReplacesAnEarlierCopyAndLeavesNothingElseBehind() throws IOException {
        var store = new LocalFileStore(root);
        store.put(Zone.CLEAN, "k", new byte[] {1, 1, 1});
        store.put(Zone.CLEAN, "k", new byte[] {2});

        assertThat(store.get(Zone.CLEAN, "k")).containsExactly(2);
        try (var files = Files.list(root.resolve("clean"))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("k");
        }
    }

    @Test
    void readingWhatIsNotThereIsABoundaryFailure() {
        var store = new LocalFileStore(root);

        assertThatThrownBy(() -> store.get(Zone.CLEAN, "missing")).isInstanceOf(BoundaryFailureException.class);
    }

    @Test
    void deleteRemovesTheFileAndRemovingWhatIsNotThereIsNotAnError() {
        var store = new LocalFileStore(root);
        store.put(Zone.CLEAN, "k", new byte[] {1});

        store.delete(Zone.CLEAN, "k");
        store.delete(Zone.CLEAN, "k");

        assertThatThrownBy(() -> store.get(Zone.CLEAN, "k")).isInstanceOf(BoundaryFailureException.class);
    }

    @Test
    void aKeyThatCouldLeaveTheZoneFolderIsRefusedWithoutEchoingIt() {
        var store = new LocalFileStore(root);

        for (String key : List.of("../escape", "a/b", "a\\b", "..", ".hidden", "", "with space", "c:evil")) {
            assertThatThrownBy(() -> store.put(Zone.CLEAN, key, new byte[] {1})).as("put '" + key + "'")
                    .isInstanceOf(BoundaryFailureException.class).hasMessageNotContaining("escape");
            assertThatThrownBy(() -> store.get(Zone.CLEAN, key)).as("get '" + key + "'")
                    .isInstanceOf(BoundaryFailureException.class);
            assertThatThrownBy(() -> store.delete(Zone.CLEAN, key)).as("delete '" + key + "'")
                    .isInstanceOf(BoundaryFailureException.class);
        }
        assertThat(root.resolveSibling("escape")).doesNotExist();
    }

    @Test
    void whatItReturnsIsACopy() {
        var store = new LocalFileStore(root);
        byte[] given = {5, 5};
        store.put(Zone.CLEAN, "k", given);
        given[0] = 0;

        byte[] read = store.get(Zone.CLEAN, "k");
        read[1] = 0;

        assertThat(store.get(Zone.CLEAN, "k")).containsExactly(5, 5);
    }
}
