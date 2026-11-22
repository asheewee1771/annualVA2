package sg.annualva.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The doc review of ticket 52 (IM8 pm-6) as a gate: {@code docs/tech-architecture.md}, which {@code /tech-arch-doc}
 * produces, must name every module, every boundary port and adapter, every Spring profile, every ADR and every table. It
 * checks that each name is present, not that the prose is right (a person reads that); what it stops is a module, an
 * adapter, a profile, an ADR or a table being added without the document being refreshed (ADR 0060 item 7). A name counts
 * only as the document writes a name: a module, class, profile or table in backticks ({@code `FileStore`}, which
 * {@code `S3FileStore`} does not satisfy), an ADR by the link to its file. Whether the generated blocks are current, a
 * migration that only alters a table or an ADR whose title changed included, is for the refresh script's {@code --check},
 * which needs Docker and so is not part of this build (README, "Architecture document"). No HTTP and no database: it reads
 * the repository's files, relative to {@code backend/} where Maven runs the tests.
 */
class DocumentationCompletenessTest {

    private static final Path BACKEND = Path.of("").toAbsolutePath();
    private static final Path MODULES = BACKEND.resolve("src/main/java/sg/annualva");
    private static final Path DOC = BACKEND.resolveSibling("docs/tech-architecture.md");
    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final String NAME = "(?:public\\.)?\"?(\\w+)\"?";
    /** The table statements of a migration, at the start of a line as the migrations write them (not in a function body). */
    private static final Pattern TABLE_STATEMENT = Pattern.compile("^(?:CREATE TABLE (?:IF NOT EXISTS )?" + NAME
            + "|DROP TABLE (?:IF EXISTS )?" + NAME + "|ALTER TABLE (?:IF EXISTS )?(?:ONLY )?" + NAME
            + " RENAME TO " + NAME + ")", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static String doc;

    @BeforeAll
    static void readTheDocument() throws IOException {
        assertThat(DOC).as("run /tech-arch-doc to produce it").exists();
        doc = Files.readString(DOC);
    }

    private static boolean named(String name) {
        return doc.contains("`" + name + "`");
    }

    @Test
    void everyModuleIsNamed() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> modules = Files.list(MODULES)) {
            modules.filter(Files::isDirectory).map(module -> module.getFileName().toString())
                    .filter(name -> !named(name)).forEach(missing::add);
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
                        .filter(name -> !named(name)).forEach(missing::add);
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
                        .filter(profile -> !named(profile)).forEach(missing::add);
            }
        }
        assertThat(missing).as("Spring profiles the document does not name").isEmpty();
    }

    @Test
    void everyAdrIsLinked() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> records = Files.list(BACKEND.resolveSibling("docs/adr"))) {
            records.map(record -> record.getFileName().toString()).filter(name -> name.endsWith(".md"))
                    .filter(name -> !doc.contains("(adr/" + name + ")")).forEach(missing::add);
        }
        assertThat(missing).as("ADRs the document does not link").isEmpty();
    }

    @Test
    void everyTableIsNamed() throws IOException {
        List<String> missing = tables().stream().filter(table -> !named(table)).toList();
        assertThat(missing).as("tables the document does not name").isEmpty();
    }

    /** The tables the migrations leave, applied in version order: created, less those dropped, renamed as renamed. */
    private static Set<String> tables() throws IOException {
        Set<String> tables = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(BACKEND.resolve("src/main/resources/db/migration"))) {
            List<Path> migrations = files.filter(file -> MIGRATION.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(DocumentationCompletenessTest::version)).toList();
            for (Path migration : migrations) {
                Matcher statement = TABLE_STATEMENT.matcher(Files.readString(migration));
                while (statement.find()) {
                    if (statement.group(1) != null) {
                        tables.add(lower(statement.group(1)));
                    } else if (statement.group(2) != null) {
                        tables.remove(lower(statement.group(2)));
                    } else {
                        tables.remove(lower(statement.group(3)));
                        tables.add(lower(statement.group(4)));
                    }
                }
            }
        }
        assertThat(tables).as("the migrations create tables").isNotEmpty();
        return tables;
    }

    private static int version(Path migration) {
        Matcher matched = MIGRATION.matcher(migration.getFileName().toString());
        assertThat(matched.matches()).isTrue();
        return Integer.parseInt(matched.group(1));
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
