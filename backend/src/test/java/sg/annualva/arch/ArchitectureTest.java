package sg.annualva.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.access.RoleGrantStore;

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
                .layer("audit").definedBy("sg.annualva.audit..")
                .layer("cycle").definedBy("sg.annualva.cycle..")
                .layer("access").definedBy("sg.annualva.access..")
                .layer("adapters").definedBy("sg.annualva.adapters..")
                // app is the composition root: it may import everything and nothing imports it
                .whereLayer("app").mayNotBeAccessedByAnyLayer()
                .whereLayer("web").mayOnlyBeAccessedByLayers("app", "adapters", "access", "audit", "cycle")
                .whereLayer("config").mayOnlyBeAccessedByLayers("app", "web", "access", "adapters", "audit", "cycle")
                .whereLayer("boundary").mayOnlyBeAccessedByLayers("app", "web", "access", "adapters")
                // the audit log is written by the modules that act (access today)
                .whereLayer("audit").mayOnlyBeAccessedByLayers("app", "access")
                .whereLayer("access").mayOnlyBeAccessedByLayers("app")
                // the calendar is a leaf below the domain modules; they will read it, nothing it reads reads back
                .whereLayer("cycle").mayOnlyBeAccessedByLayers("app")
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
                .should().beAnnotatedWith(PreAuthorize.class)
                .because("every endpoint is denied by default and opened only to roles in the permission matrix (ac-1);"
                        + " the few anonymous endpoints are listed in SecurityConfig.PUBLIC_PATHS and say"
                        + " so with @PreAuthorize(\"permitAll()\")")
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

    /**
     * The anonymous list is the single place that opens an endpoint to nobody in particular, so it
     * must agree with the method-level rules: an endpoint says permitAll() exactly when its path is
     * on the list.
     */
    @Test
    void anonymousEndpointsAreExactlyThoseOnTheAnonymousList() throws Exception {
        Field field = Class.forName("sg.annualva.app.SecurityConfig").getDeclaredField("PUBLIC_PATHS");
        field.setAccessible(true);
        String[] publicPaths = (String[]) field.get(null);
        var matcher = new AntPathMatcher();
        var disagreements = new ArrayList<String>();
        for (JavaClass controller : main) {
            if (!controller.isAnnotatedWith(RestController.class)) {
                continue;
            }
            Class<?> type = controller.reflect();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                boolean anonymous = rule != null && rule.value().equals("permitAll()");
                for (String path : mapping.path()) {
                    boolean listed = Arrays.stream(publicPaths).anyMatch(pattern -> matcher.match(pattern, path));
                    if (anonymous != listed) {
                        disagreements.add(type.getSimpleName() + "." + method.getName() + " " + path
                                + (anonymous ? " says permitAll() but is not on the anonymous list"
                                        : " is on the anonymous list but does not say permitAll()"));
                    }
                }
            }
        }
        assertThat(disagreements).isEmpty();
    }

    /**
     * Story 76 by construction: roles change only through the role store, and only the classes named
     * here write to it. A new writer (the Register apply, the role-holder review) is added to this list
     * on purpose, in the ticket that brings it, and never a controller reached by a person's own request.
     */
    @Test
    void onlyNamedClassesWriteRoleGrants() {
        noClasses().that().areNotAssignableTo(RoleGrantStore.class)
                .and().doNotHaveFullyQualifiedName("sg.annualva.access.VaPocSync")
                .and().doNotHaveFullyQualifiedName("sg.annualva.access.RoleResolver")
                .and().doNotHaveFullyQualifiedName("sg.annualva.access.SignInService")
                .should().dependOnClassesThat().areAssignableTo(RoleGrantStore.class)
                .because("roles are given only by configuration, a System Register apply and the role-holder review;"
                        + " sign-in attaches grants made by email and role resolution only reads them")
                .check(main);
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(RoleGrantStore.class)
                .because("there is no endpoint through which a person can grant a role (story 76)")
                .check(main);
    }
}
