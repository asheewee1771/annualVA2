package sg.annualva.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.config.DevLocal;
import sg.annualva.config.NotDevLocal;

/**
 * Ticket 63, ADR 0058: nothing of the dev-local profile can exist outside it. Every top-level class of the local adapters
 * and of the persona sign-in is annotated {@code @Profile("dev-local")}, and nothing that is not part of it depends on one,
 * so a class that forgot the annotation, or a deployed class that reached for one, fails the build.
 */
class DevLocalArchTest {

    private static final String DEV_LOCAL = "dev-local";

    private static JavaClasses main;

    @BeforeAll
    static void importMain() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sg.annualva");
    }

    /** The top-level class this one is, or is nested in. */
    private static JavaClass outermost(JavaClass type) {
        JavaClass current = type;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    private static boolean ofTheProfile(JavaClass type) {
        JavaClass outer = outermost(type);
        return outer.getPackageName().startsWith("sg.annualva.adapters.local")
                || (outer.getPackageName().equals("sg.annualva.access") && outer.getSimpleName().startsWith("DevLocal"));
    }

    private static final DescribedPredicate<JavaClass> PART_OF_THE_PROFILE =
            DescribedPredicate.describe("part of the dev-local profile", DevLocalArchTest::ofTheProfile);

    @Test
    void theProfileHasClassesForTheRuleToCheck() {
        assertThat(main.stream().filter(DevLocalArchTest::ofTheProfile).map(JavaClass::getSimpleName).toList())
                .contains("DevLocalGuard", "DevLocalSignIn", "DevLocalController", "LocalFileStore", "LocalMalwareScanner",
                        "LocalMailRelay", "LocalIdentityStatus");
    }

    @Test
    void everyTopLevelClassOfTheProfileIsAnnotatedWithIt() {
        classes().that(PART_OF_THE_PROFILE).and().areTopLevelClasses()
                .and(DescribedPredicate.describe("not package-info", type -> !type.getSimpleName().equals("package-info")))
                .should().beAnnotatedWith(DevLocal.class)
                .because("a class of the dev-local profile that is not gated by it could run in a deployed environment")
                .check(main);
    }

    @Test
    void theTwoAnnotationsAreTheProfileAndItsNegation() {
        assertThat(Arrays.asList(DevLocal.class.getAnnotation(Profile.class).value())).containsExactly(DEV_LOCAL);
        assertThat(Arrays.asList(NotDevLocal.class.getAnnotation(Profile.class).value())).containsExactly("!" + DEV_LOCAL);
    }

    /** The single sign-on client and the stubs that ticket 51 replaces must each step aside for the local adapters. */
    @Test
    void everyBeanOfTheSingleSignOnClientAndOfThePendingAdaptersStepsAsideUnderDevLocal() {
        classes().that().resideInAnyPackage("sg.annualva.adapters.oidc", "sg.annualva.adapters.pending")
                .and().areTopLevelClasses()
                .and(DescribedPredicate.describe("a Spring bean or its properties", type ->
                        type.isAnnotatedWith(Component.class) || type.isAnnotatedWith(Configuration.class)
                                || type.isAnnotatedWith(RestController.class)
                                || type.isAnnotatedWith(ConfigurationProperties.class)))
                .should().beAnnotatedWith(NotDevLocal.class)
                .because("under dev-local there is no identity provider to register and the local adapters take the stubs' place")
                .allowEmptyShould(false)
                .check(main);
        classes().that().haveFullyQualifiedName("sg.annualva.access.OidcLogin")
                .or().haveFullyQualifiedName("sg.annualva.access.OidcSignInUserService")
                .or().haveFullyQualifiedName("sg.annualva.access.StepUpAuthenticationProvider")
                .or().haveFullyQualifiedName("sg.annualva.access.OidcStepUpStarter")
                .should().beAnnotatedWith(NotDevLocal.class)
                .check(main);
    }

    @Test
    void nothingOutsideTheProfileDependsOnIt() {
        noClasses().that(DescribedPredicate.not(PART_OF_THE_PROFILE))
                .should().dependOnClassesThat(PART_OF_THE_PROFILE)
                .because("a class that always exists must not need one that exists only under dev-local")
                .check(main);
    }
}
