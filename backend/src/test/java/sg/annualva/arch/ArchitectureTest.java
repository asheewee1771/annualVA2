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
                .layer("scan").definedBy("sg.annualva.scan..")
                .layer("parser").definedBy("sg.annualva.parser..")
                .layer("register").definedBy("sg.annualva.register..")
                .layer("submission").definedBy("sg.annualva.submission..")
                .layer("finding").definedBy("sg.annualva.finding..")
                .layer("ingestion").definedBy("sg.annualva.ingestion..")
                .layer("dashboard").definedBy("sg.annualva.dashboard..")
                .layer("notification").definedBy("sg.annualva.notification..")
                .layer("export").definedBy("sg.annualva.export..")
                .layer("adapters").definedBy("sg.annualva.adapters..")
                // app is the composition root: it may import everything and nothing imports it
                .whereLayer("app").mayNotBeAccessedByAnyLayer()
                .whereLayer("web").mayOnlyBeAccessedByLayers("app", "adapters", "access", "audit", "cycle", "register", "submission", "finding", "ingestion", "dashboard", "notification", "export")
                .whereLayer("config").mayOnlyBeAccessedByLayers("app", "web", "access", "adapters", "audit", "cycle")
                // the exports keep the files they make in the file store's export zone
                .whereLayer("boundary").mayOnlyBeAccessedByLayers("app", "web", "access", "adapters", "scan", "ingestion", "export")
                // quarantine and malware scan: one module holds the upload steps both the Register upload and the pipeline use
                .whereLayer("scan").mayOnlyBeAccessedByLayers("app", "register", "ingestion")
                // the report readers are a leaf; the upload pipeline sniffs, checks and parses files with them, and the Findings keep what they read; the submissions clean a return's comment by the one text rule (ReportText)
                .whereLayer("parser").mayOnlyBeAccessedByLayers("app", "submission", "finding", "ingestion")
                // the audit log is written by the modules that act (access, the register and the submissions today)
                .whereLayer("audit").mayOnlyBeAccessedByLayers("app", "access", "register", "submission", "finding", "ingestion", "export")
                // the domain modules ask access who is acting and whether they may reach a System
                .whereLayer("access").mayOnlyBeAccessedByLayers("app", "register", "submission", "finding", "ingestion", "dashboard", "notification", "export")
                // the register is a domain module: the submissions start from its Systems, the dashboards list them and the notifications address the people it names
                .whereLayer("register").mayOnlyBeAccessedByLayers("app", "submission", "dashboard", "notification")
                // the submissions sit above the register and the calendar; the dashboards read them (the inbox shows a Draft), the file pipeline and the Findings ask which System a VA Submission belongs to, and the notifications listen to their events
                .whereLayer("submission").mayOnlyBeAccessedByLayers("app", "dashboard", "finding", "ingestion", "notification")
                // the Findings sit above the submissions and the parsers; the upload pipeline hands them what a parser read, and the dashboards count them
                .whereLayer("finding").mayOnlyBeAccessedByLayers("app", "ingestion", "dashboard", "export")
                // the Scan Report upload pipeline sits above the submissions and the Findings; nothing reads it
                .whereLayer("ingestion").mayOnlyBeAccessedByLayers("app")
                // the dashboards are read models over the modules below them; only the exports read them (the management summary is the Cycle matrix's rows)
                .whereLayer("dashboard").mayOnlyBeAccessedByLayers("app", "export")
                // the notifications listen to the modules below them after each commit; nothing reads them
                .whereLayer("notification").mayOnlyBeAccessedByLayers("app")
                // the exports sit on top: nothing reads them
                .whereLayer("export").mayOnlyBeAccessedByLayers("app")
                // the calendar is a leaf below the domain modules (the register dates a System by it, a submission and the inbox by its Quarter, the Findings and the upload pipeline's age check read today's business date from it); nothing it reads reads back
                .whereLayer("cycle").mayOnlyBeAccessedByLayers("app", "register", "submission", "finding", "ingestion",
                        "dashboard", "notification", "export")
                // production adapters are wired by Spring type only; no module imports them
                .whereLayer("adapters").mayNotBeAccessedByAnyLayer()
                .check(main);
    }

    /**
     * ADR 0032 item 5 as a gate: what the SMO/Tech Lead prepares in a VA Submission is written only under the VA
     * Submission's shared lock, which {@code Submissions.lockForEdit} takes and which no submit can pass. Each statement that
     * writes it takes an {@code EditTarget}, and only {@code lockForEdit} makes one (its constructor is private), so a new
     * write path cannot skip the lock or the state check. The unlocked {@code precheckEdit} exists only for the upload's
     * early refusal, before its bytes are quarantined, and the class that uses it must also take the lock to keep the file.
     */
    @Test
    void writesToPreparedContentTakeTheEditLock() throws Exception {
        Class<?> editTarget = Class.forName("sg.annualva.submission.Submissions$EditTarget");
        String[][] writes = {
            {"sg.annualva.ingestion.ReportFileStore", "insert"},
            {"sg.annualva.ingestion.ReportFileStore", "confirm"},
            {"sg.annualva.finding.ScanReportStore", "lock"},
            {"sg.annualva.finding.ScanReportStore", "set"},
            {"sg.annualva.finding.ScanReportStore", "insertManual"},
            {"sg.annualva.finding.Proposals", "lock"},
            {"sg.annualva.finding.Proposals", "fields"},
            {"sg.annualva.finding.Proposals", "newBatch"},
        };
        var missing = new ArrayList<String>();
        for (String[] write : writes) {
            Method[] named = Arrays.stream(Class.forName(write[0]).getDeclaredMethods())
                    .filter(method -> method.getName().equals(write[1])).toArray(Method[]::new);
            if (named.length == 0 || !Arrays.stream(named).allMatch(
                    method -> Arrays.asList(method.getParameterTypes()).contains(editTarget))) {
                missing.add(write[0] + "." + write[1]);
            }
        }
        assertThat(missing).as("writes that do not take an EditTarget").isEmpty();
        assertThat(Arrays.stream(editTarget.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())))
                .as("only Submissions.lockForEdit makes an EditTarget").isTrue();

        noClasses().that().doNotHaveFullyQualifiedName("sg.annualva.ingestion.ReportFiles")
                .and().resideOutsideOfPackage("sg.annualva.submission..")
                .should().callMethod(sg.annualva.submission.Submissions.class, "precheckEdit", java.util.UUID.class,
                        sg.annualva.submission.Submissions.Refusal.class)
                .because("the unlocked early check is only for refusing an upload before its bytes are quarantined")
                .check(main);
        methods().that().areDeclaredIn(Class.forName("sg.annualva.ingestion.ReportFiles"))
                .and().haveName("keep")
                .should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
                        "take the edit lock before keeping a file") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaMethod method,
                            com.tngtech.archunit.lang.ConditionEvents events) {
                        boolean locks = method.getMethodCallsFromSelf().stream()
                                .anyMatch(call -> call.getTarget().getName().equals("lockForEdit"));
                        if (!locks) {
                            events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(method,
                                    method.getFullName() + " keeps a file without Submissions.lockForEdit"));
                        }
                    }
                })
                .check(main);
    }

    /**
     * Ticket 28 as a gate: a review is written only once {@code Submissions.lockForReview} has held the VA Submission In
     * Review, checked segregation of duties and recorded the reviewing step. Every method of {@code ReviewStore} that is
     * not one of its named reads takes a {@code ReviewTarget}, so a new write (ticket 29's batch review, say) is checked by
     * default; only {@code lockForReview} makes one (its constructor is private).
     */
    @Test
    void writesOfAReviewTakeTheReviewLock() throws Exception {
        Class<?> reviewTarget = Class.forName("sg.annualva.submission.Submissions$ReviewTarget");
        var reads = java.util.Set.of("latestOfReports", "review", "disputedFindings",
                "disputedReports");
        var missing = Arrays.stream(Class.forName("sg.annualva.finding.ReviewStore").getDeclaredMethods())
                .filter(method -> !Modifier.isPrivate(method.getModifiers()) && !method.isSynthetic())
                .filter(method -> !reads.contains(method.getName()))
                .filter(method -> !Arrays.asList(method.getParameterTypes()).contains(reviewTarget))
                .map(Method::getName)
                .toList();
        assertThat(missing).as("review writes that do not take a ReviewTarget").isEmpty();
        assertThat(Arrays.stream(reviewTarget.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())))
                .as("only Submissions.lockForReview makes a ReviewTarget").isTrue();
    }

    /**
     * ADR 0035 as a gate: every command that records a person at a step of a VA Submission first asks the one
     * segregation-of-duties rule, {@code SegregationOfDuties.require}, so a later step command (return, pass to
     * endorsement, endorse) cannot skip it. Only {@code Submissions} records a step.
     */
    @Test
    void everyCommandThatRecordsAStepAsksSegregationOfDuties() throws Exception {
        Class<?> store = Class.forName("sg.annualva.submission.SubmissionStore");
        Class<?> sod = Class.forName("sg.annualva.submission.SegregationOfDuties");
        noClasses().that().doNotHaveFullyQualifiedName(sg.annualva.submission.Submissions.class.getName())
                .should().callMethod("sg.annualva.submission.SubmissionStore", "recordParticipation", "java.util.UUID",
                        "java.util.UUID", "sg.annualva.submission.SubmissionStep", "java.time.Instant")
                .because("a step is recorded only by a command of Submissions, which asks segregation of duties first")
                .check(main);
        methods().that().areDeclaredIn(sg.annualva.submission.Submissions.class)
                .should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
                        "ask segregation of duties before recording a step") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaMethod method,
                            com.tngtech.archunit.lang.ConditionEvents events) {
                        var calls = method.getMethodCallsFromSelf();
                        boolean records = calls.stream().anyMatch(call -> call.getTargetOwner().isEquivalentTo(store)
                                && call.getName().equals("recordParticipation"));
                        boolean asks = calls.stream().anyMatch(call -> call.getTargetOwner().isEquivalentTo(sod)
                                && call.getName().equals("require"));
                        if (records && !asks) {
                            events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(method,
                                    method.getFullName() + " records a step without SegregationOfDuties.require"));
                        }
                    }
                })
                .check(main);
    }

    /**
     * ADR 0033: {@code RegisterSystems.everyActive} lists every System without asking the access guard, because the
     * Overdue sweep acts for nobody. Only that job may call it, so no request can list Systems past the guard (ADR 0013).
     * A later scheduled job that needs it adds itself here.
     */
    @Test
    void onlyTheOverdueSweepListsSystemsPastTheAccessGuard() throws Exception {
        // Named by reflection, so a rename of the method or of its caller breaks this test instead of emptying the rule.
        String method = sg.annualva.register.RegisterSystems.class.getMethod("everyActive").getName();
        noClasses().that().doNotHaveFullyQualifiedName(sg.annualva.submission.OverdueSweep.class.getName())
                .should().callMethod(sg.annualva.register.RegisterSystems.class, method)
                .because("it skips the access guard, so only a scheduled job, which acts for nobody, may list every System")
                .check(main);
    }

    /**
     * ADR 0043: {@code RegisterSystems.named} reads a System and the people it names without asking the access guard,
     * because the notifications address whoever acts next after a move has committed, for nobody. Only the notification
     * module may call it, so no request can read the Register past the guard (ADR 0013).
     */
    @Test
    void onlyTheNotificationsReadTheNamedPeoplePastTheAccessGuard() throws Exception {
        String method = sg.annualva.register.RegisterSystems.class.getMethod("named", java.util.UUID.class).getName();
        noClasses().that().resideOutsideOfPackage("sg.annualva.notification..")
                .should().callMethod(sg.annualva.register.RegisterSystems.class, method, java.util.UUID.class)
                .because("it skips the access guard, so only the notifications, which act for nobody, may read it")
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
    void everyBoundaryPortIsImplementedOnlyByAdapters() {
        noClasses().that().resideOutsideOfPackage("sg.annualva.adapters..")
                .should().implement(sg.annualva.boundary.MalwareScanner.class)
                .orShould().implement(sg.annualva.boundary.FileStore.class)
                .because("production implementations of the boundary ports live under sg.annualva.adapters")
                .check(main);
    }

    @Test
    void xmlReadersAreMadeOnlyInSecureXml() {
        noClasses().that().doNotHaveFullyQualifiedName("sg.annualva.parser.xml.SecureXml")
                .should().callMethod(javax.xml.stream.XMLInputFactory.class, "newFactory")
                .orShould().callMethod(javax.xml.stream.XMLInputFactory.class, "newInstance")
                .orShould().callMethod(javax.xml.parsers.DocumentBuilderFactory.class, "newInstance")
                .orShould().callMethod(javax.xml.parsers.SAXParserFactory.class, "newInstance")
                .orShould().callMethod(javax.xml.transform.TransformerFactory.class, "newInstance")
                .because("report files are untrusted XML, and every reader that touches one must have DTDs and external"
                        + " entities off, which SecureXml sets in one place")
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
     * here write to it. RegisterRoles is the Register apply's writer; a new one (the role-holder review) is added to this list
     * on purpose, in the ticket that brings it, and never a controller reached by a person's own request.
     */
    @Test
    void onlyNamedClassesWriteRoleGrants() {
        noClasses().that().areNotAssignableTo(RoleGrantStore.class)
                .and().doNotHaveFullyQualifiedName("sg.annualva.access.VaPocSync")
                .and().doNotHaveFullyQualifiedName("sg.annualva.access.RoleResolver")
                .and().doNotHaveFullyQualifiedName("sg.annualva.access.RegisterRoles")
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
