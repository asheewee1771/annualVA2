package sg.annualva.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module, naming and data-access conventions from {@code artifacts/arch-test-plan.md}
 * that {@link ArchitectureTest} does not already hold. Imports the main classes only.
 */
class ModuleConventionsArchTest {

    private static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";

    private static JavaClasses main;

    @BeforeAll
    static void importMain() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sg.annualva");
    }

    @Test
    void modulesAreFreeOfCycles() {
        SlicesRuleDefinition.slices().matching("sg.annualva.(*)..").should().beFreeOfCycles()
                .because("the modules form a fixed downward order, so no module may depend on one that depends back on it")
                .check(main);
    }

    @Test
    void platformModulesDoNotDependOnBusinessModules() {
        noClasses().that().resideInAnyPackage("sg.annualva.web..", "sg.annualva.config..", "sg.annualva.jdbc..",
                        "sg.annualva.boundary..", "sg.annualva.monitoring..",
                        "sg.annualva.audit..", "sg.annualva.access..", "sg.annualva.cycle..", "sg.annualva.scan..",
                        "sg.annualva.parser..")
                .should().dependOnClassesThat().resideInAnyPackage("sg.annualva.register..", "sg.annualva.submission..",
                        "sg.annualva.finding..", "sg.annualva.ingestion..", "sg.annualva.dashboard..",
                        "sg.annualva.notification..", "sg.annualva.export..", "sg.annualva.retention..")
                .because("dependencies run from the business modules to the platform modules and never back")
                .check(main);
    }

    @Test
    void controllersNeverReachTheDataLayer() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat(resideInAPackage("sg.annualva..").and(simpleNameEndingWith("Store")))
                .orShould().dependOnClassesThat().haveFullyQualifiedName(JDBC_CLIENT)
                .because("a controller goes through a service; the data layer is the *Store classes (ADR 0001)")
                .check(main);
    }

    @Test
    void transactionsAreNotOpenedByControllers() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().beAnnotatedWith(Transactional.class)
                .because("a transaction belongs to the service that owns the use case")
                .allowEmptyShould(true)
                .check(main);
        noMethods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should().beAnnotatedWith(Transactional.class)
                .because("a transaction belongs to the service that owns the use case")
                .allowEmptyShould(true)
                .check(main);
    }

    @Test
    void noClassLivesInTheDefaultPackage() {
        JavaClasses compiled = new ClassFileImporter().importPath(Path.of("target", "classes"));
        classes().should().resideInAPackage("sg.annualva..")
                .because("every production class is in a named package")
                .check(compiled);
    }

    @Test
    void controllersAreNamedAsControllers() {
        classes().that().areAnnotatedWith(RestController.class).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Controller")
                .check(main);
    }

    /**
     * The System Register's table is read in SQL only by the Register's own store: every other module reads Systems
     * through {@code RegisterSystems} (ADR 0043, 0044). The one exception is the disposal executor, which deletes
     * records in foreign-key order through its own store (ADR 0050). Reads every static SQL text of every store.
     */
    @Test
    void onlyTheRegisterStoreNamesTheSystemTableInSql() throws IllegalAccessException {
        java.util.regex.Pattern table = java.util.regex.Pattern.compile("\\bva_system\\b");
        java.util.List<String> offenders = StaticSql.of(main).stream()
                .filter(sql -> !sql.packageName().equals("sg.annualva.register")
                        && !sql.store().equals("sg.annualva.retention.DisposalStore"))
                .filter(sql -> table.matcher(sql.text()).find())
                .map(StaticSql.Constant::name)
                .toList();
        org.assertj.core.api.Assertions.assertThat(offenders)
                .as("SQL outside the Register's store that reads va_system; read Systems through RegisterSystems")
                .isEmpty();
    }

    @Test
    void repositoriesAreNamedAsStores() {
        classes().that().areAnnotatedWith(Repository.class).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Store")
                .check(main);
    }

    @Test
    void productionCodeNeverImportsATestFramework() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.junit..", "org.mockito..", "org.testng..",
                        "org.assertj..")
                .because("test frameworks belong on the test classpath only")
                .check(main);
    }

    @Test
    void supportingEvidenceIsNeverReadByAParserOrAReader() {
        noClasses().that().haveSimpleName("EvidenceFormat")
                .should().dependOnClassesThat().resideInAnyPackage("sg.annualva.parser..", "javax.xml..", "org.w3c.dom..",
                        "org.xml.sax..", "tools.jackson..", "java.util.zip..", "java.io..", "java.nio.file..")
                .because("evidence is HTML or PDF from an untrusted person: its leading bytes are compared and nothing reads it (ADR 0026)")
                .check(main);
    }

    @Test
    void onlyTheExportModuleUsesThePdfLibrary() {
        noClasses().that().resideOutsideOfPackage("sg.annualva.export..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.pdfbox..", "org.apache.fontbox..")
                .because("PDFBox writes the management summary; a PDF someone uploads is never parsed (ADR 0026, ADR 0054)")
                .check(main);
    }

    @Test
    void onlyTheS3AdapterUsesTheAwsSdk() {
        noClasses().that().resideOutsideOfPackage("sg.annualva.adapters.s3..")
                .should().dependOnClassesThat().resideInAnyPackage("software.amazon.awssdk..")
                .because("the object store is reached through the FileStore port; the SDK is the S3 adapter's detail (ADR 0059)")
                .check(main);
    }

    @Test
    void jdbcClientIsUsedOnlyInsideStores() {
        noClasses().that().haveSimpleNameNotEndingWith("Store")
                .should().dependOnClassesThat().haveFullyQualifiedName(JDBC_CLIENT)
                .because("SQL stays in the *Store classes, where it is written and reviewed as static text (ADR 0001)")
                .check(main);
    }
}
