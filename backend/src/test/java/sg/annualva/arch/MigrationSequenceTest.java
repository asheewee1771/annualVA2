package sg.annualva.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Flyway migrations run as one gapless sequence of whole versions, V1, V2, … (ADR 0047 item 6, decided 2026-10-25):
 * a ticket may not take its own number such as V129 or V141_35, which would sort out of merge order and need Flyway's
 * out-of-order mode. A new migration takes the next number after the highest.
 */
class MigrationSequenceTest {

    private static final Pattern NAME = Pattern.compile("V[1-9]\\d*__[a-z0-9_]+\\.sql");

    @Test
    void migrationsAreOneGaplessSequenceOfWholeVersions() throws IOException, URISyntaxException {
        Path folder = Path.of(Objects.requireNonNull(
                getClass().getClassLoader().getResource("db/migration"), "db/migration on the classpath").toURI());
        List<String> names;
        try (Stream<Path> files = Files.list(folder)) {
            names = files.map(file -> file.getFileName().toString()).sorted().toList();
        }

        assertThat(names).as("every migration is V<whole number>__<description>.sql")
                .allMatch(name -> NAME.matcher(name).matches());
        List<Integer> versions = names.stream()
                .map(name -> Integer.valueOf(name.substring(1, name.indexOf("__"))))
                .sorted()
                .toList();
        assertThat(versions).as("the versions run 1, 2, … with no gap and no repeat")
                .containsExactlyElementsOf(IntStream.rangeClosed(1, versions.size()).boxed().toList());
    }
}
