package sg.annualva.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The doc review of ticket 52 (IM8 pm-6) as a gate: {@code docs/tech-architecture.md}, which {@code /tech-arch-doc}
 * produces, must name every module, every boundary port and adapter, every Spring profile, every ADR and every table. It
 * checks that each name is present, not that the prose is right (a person reads that); what it stops is a module, an
 * adapter, a profile, an ADR or a table being added without the document being refreshed (ADR 0060 item 7). No HTTP and
 * no database: it reads the repository's files, relative to {@code backend/} where Maven runs the tests.
 */
class DocumentationCompletenessTest {

    private static final Path BACKEND = Path.of("").toAbsolutePath();
    private static final Path MODULES = BACKEND.resolve("src/main/java/sg/annualva");
    private static final Path DOC = BACKEND.resolveSibling("docs/tech-architecture.md");
    private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE\\s+(\\w+)", Pattern.MULTILINE);

    private static String doc;

    @BeforeAll
    static void readTheDocument() throws IOException {
        assertThat(DOC).as("run /tech-arch-doc to produce it").exists();
        doc = Files.readString(DOC);
    }

    @Test
    void everyModuleIsNamed() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> modules = Files.list(MODULES)) {
            modules.filter(Files::isDirectory).map(module -> module.getFileName().toString())
                    .filter(name -> !doc.contains("`" + name + "`")).forEach(missing::add);
        }
        assertThat(missing).as("modules (packages of sg.annualva) the document does not name").isEmpty();
    }

    @Test
    void everyBoundaryPortAndAdapterIsNamed() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String folder : new String[] {"boundary", "adapters"}) {
            try (Stream<Path> files = Files.walk(MODULES.resolve(folder))) {
                files.filter(file -> file.toString().endsWith(".java") && !file.endsWith("package-info.java"))
                        .map(file -> file.getFileName().toString().replace(".java", ""))
                        .filter(name -> !doc.contains(name)).forEach(missing::add);
            }
        }
        assertThat(missing).as("boundary ports and adapter classes the document does not name").isEmpty();
    }

    @Test
    void everyProfileIsNamed() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String folder : new String[] {"src/main/resources", "src/test/resources"}) {
            try (Stream<Path> files = Files.list(BACKEND.resolve(folder))) {
                files.map(file -> file.getFileName().toString())
                        .filter(name -> name.startsWith("application-") && name.endsWith(".yml"))
                        .map(name -> name.substring("application-".length(), name.length() - ".yml".length()))
                        .filter(profile -> !doc.contains("`" + profile + "`")).forEach(missing::add);
            }
        }
        assertThat(missing).as("Spring profiles the document does not name").isEmpty();
    }

    @Test
    void everyAdrIsNamedByItsFileName() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> records = Files.list(BACKEND.resolveSibling("docs/adr"))) {
            records.map(record -> record.getFileName().toString()).filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - ".md".length()))
                    .filter(name -> !doc.contains(name)).forEach(missing::add);
        }
        assertThat(missing).as("ADRs the document does not name").isEmpty();
    }

    @Test
    void everyTableIsNamed() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> migrations = Files.list(BACKEND.resolve("src/main/resources/db/migration"))) {
            for (Path migration : migrations.filter(file -> file.toString().endsWith(".sql")).toList()) {
                Matcher table = CREATE_TABLE.matcher(Files.readString(migration));
                while (table.find()) {
                    if (!doc.contains("`" + table.group(1).toLowerCase(java.util.Locale.ROOT) + "`")) {
                        missing.add(table.group(1));
                    }
                }
            }
        }
        assertThat(missing).as("tables the document does not name").isEmpty();
    }
}
