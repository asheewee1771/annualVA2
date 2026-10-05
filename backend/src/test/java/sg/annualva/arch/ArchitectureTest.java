package sg.annualva.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module dependency rules and coding standards that keep the modules deep and the IM8
 * controls provable. Imports the main classes only; fakes and harness code are never on main.
 */
class ArchitectureTest {

    private static JavaClasses main;

    @BeforeAll
    static void importMain() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sg.annualva");
    }

    @Test
    void modulesDependOnlyDownwards() {
        Architectures.layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("app").definedBy("sg.annualva.app..")
                .layer("web").definedBy("sg.annualva.web..")
                .layer("config").definedBy("sg.annualva.config..")
                .layer("boundary").definedBy("sg.annualva.boundary..")
                .layer("access").definedBy("sg.annualva.access..")
                .layer("adapters").definedBy("sg.annualva.adapters..")
                // app is the composition root: it may import everything and nothing imports it
                .whereLayer("app").mayNotBeAccessedByAnyLayer()
                .whereLayer("web").mayOnlyBeAccessedByLayers("app", "adapters")
                .whereLayer("config").mayOnlyBeAccessedByLayers("app", "web", "access", "adapters")
                .whereLayer("boundary").mayOnlyBeAccessedByLayers("app", "web", "access", "adapters")
                .whereLayer("access").mayOnlyBeAccessedByLayers("app")
                // production adapters are wired by Spring type only; no module imports them
                .whereLayer("adapters").mayNotBeAccessedByAnyLayer()
                .check(main);
    }

    @Test
    void noStandardStreamsLoggingOrGenericExceptions() {
        NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(main);
        NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(main);
        NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(main);
        NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(main);
        NO_CLASSES_SHOULD_USE_JODATIME.check(main);
    }

    @Test
    void timeIsReadOnlyThroughTheClockBean() {
        noClasses().that().resideOutsideOfPackage("sg.annualva.app")
                .should().callMethod(java.time.Clock.class, "systemUTC")
                .orShould().callMethod(java.time.Clock.class, "systemDefaultZone")
                .orShould().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(java.time.LocalDate.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(java.time.ZonedDateTime.class, "now")
                .orShould().callMethod(java.time.OffsetDateTime.class, "now")
                .because("tests set the time through the controllable Clock")
                .check(main);
    }

    @Test
    void everyControllerMethodIsEitherPublicByDesignOrGuarded() {
        methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().arePublic()
                .and().areDeclaredInClassesThat().resideOutsideOfPackage("sg.annualva.web")
                .should().beAnnotatedWith(PreAuthorize.class)
                .because("every endpoint is denied by default and opened only to roles in the permission matrix (ac-1);"
                        + " the few anonymous endpoints are listed in SecurityConfig.PUBLIC_PATHS and, outside"
                        + " sg.annualva.web, say so with @PreAuthorize(\"permitAll()\")")
                .allowEmptyShould(true)
                .check(main);
    }

    @Test
    void fakesNeverShipOnMain() {
        noClasses().should().haveSimpleNameContaining("Fake")
                .orShould().haveSimpleNameContaining("Stub")
                .orShould().haveSimpleNameContaining("InMemory")
                .orShould().haveSimpleNameContaining("Mutable")
                .because("boundary fakes live only in the Seam 1 harness")
                .check(main);
    }

    @Test
    void boundaryPortsAreImplementedOnlyByAdapters() {
        noClasses().that().resideOutsideOfPackage("sg.annualva.adapters..")
                .should().implement(sg.annualva.boundary.IdentityProvider.class)
                .because("production implementations of the boundary ports live under sg.annualva.adapters")
                .check(main);
    }

    @Test
    void scheduledJobsAreNamedAsJobs() {
        methods().that().areAnnotatedWith(Scheduled.class)
                .should().beDeclaredInClassesThat().haveSimpleNameEndingWith("Job")
                .allowEmptyShould(true)
                .check(main);
    }
}
