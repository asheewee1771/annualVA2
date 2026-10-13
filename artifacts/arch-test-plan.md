# Test Plan

Generated: 2026-10-13
Stack: Java 21 / Spring Boot 4.0 (Spring Web MVC, Spring Security, JdbcClient, Flyway; no JPA, no MapStruct) / Maven; TypeScript / React 19 / Vite / dependency-cruiser
Architecture style: modular monolith (business-named modules with no layer folders inside; the modules form a fixed downward order). Confirmed from the code and the `package-info.java` charters; ADRs 0013, 0014 and 0018 agree.
Dependency direction: down (`app` over `adapters`, `dashboard`, `submission`, `register`, `access`, `audit`, `cycle`, `web`, `boundary`, `config`). Module imports checked: none points back up and there are no cycles.
Frontend: React 19 + Vite, role-based screens (`src/screens/<role>`, `shared`) over `app`, `api`, `components`, `hooks`; no `features/` folder, no barrels, no store

The hand-written `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` and `frontend/.dependency-cruiser.cjs` already enforce part of this plan. Rows already covered say `Implemented in ...` in Notes, so `/arch-tests-gen` should reuse or skip them, not write a second copy. Rows with no such note are new.

---

## Backend — Core

### Cycles

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_cyclic_dependencies` | ArchUnit | No module depends on another module that depends back on it | — | `SlicesRuleDefinition.slices().matching("sg.annualva.(*)..").should().beFreeOfCycles()` | Holds today. `modulesDependOnlyDownwards` limits who may call whom but is not itself a cycle check. | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |

### Layer Boundaries

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `module_dependencies_point_down` | ArchUnit | Modules depend only downwards; `app` is the composition root and nothing imports `app` or `adapters` | — | `Architectures.layeredArchitecture().consideringOnlyDependenciesInLayers()` with one layer per module and `mayOnlyBeAccessedByLayers` rows | Implemented in `ArchitectureTest#modulesDependOnlyDownwards`. This is the modular-monolith form of `layer_access_rules` and `no_forbidden_package_deps`. Each new module adds a layer and an arrow in its own ticket. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `no_shared_depends_on_feature` | ArchUnit | Platform modules never depend on business modules (business to platform only) | — | `noClasses().that().resideInAnyPackage("sg.annualva.web..", "sg.annualva.config..", "sg.annualva.boundary..", "sg.annualva.audit..", "sg.annualva.access..", "sg.annualva.cycle..").should().dependOnClassesThat().resideInAnyPackage("sg.annualva.register..", "sg.annualva.submission..", "sg.annualva.dashboard..")` | Holds today and matches the `package-info.java` charters. Covered indirectly by the layered rule; this row names the shared/business split so a new business module has to be listed. | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `no_business_logic_in_controllers` | ArchUnit | A controller never reaches the data layer; it goes through a service | — | `noClasses().that().areAnnotatedWith(RestController.class).should().dependOnClassesThat().haveSimpleNameEndingWith("Store").orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")` | Holds today. Adapted: there is no `controller` or `repository` package; the data layer is the `*Store` classes (ADR 0001). | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `no_transactional_outside_service` | ArchUnit | Transactions are managed at the service boundary, not on controllers | — | `noClasses().that().areAnnotatedWith(RestController.class).should().beAnnotatedWith(Transactional.class).allowEmptyShould(true)` | Holds today. The `..domain` half of the catalogue rule does not apply (no `domain` package). | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |

### Naming Conventions

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_classes_in_default_package` | ArchUnit | Every class is in a named package | — | `classes().should().resideInAnyPackage("sg.annualva..")` over an import of the whole classpath | Holds today. `ArchitectureTest` imports only `sg.annualva`, so the check is only meaningful if the gen step imports the whole main classpath. | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `naming_conventions_controller` | ArchUnit | Every `@RestController` class is named `*Controller` | — | `classes().that().areAnnotatedWith(RestController.class).and().areTopLevelClasses().should().haveSimpleNameEndingWith("Controller")` | Holds today. Annotation-based because there is no `controller` package. `ApiExceptionHandler` is `@RestControllerAdvice` and is not matched. | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `naming_conventions_store` | ArchUnit | Every `@Repository` class is named `*Store` | — | `classes().that().areAnnotatedWith(Repository.class).and().areTopLevelClasses().should().haveSimpleNameEndingWith("Store")` | Holds today (`AppUserStore`, `RoleGrantStore`, `AuditStore`, `RegisterStore`, `SubmissionStore`). | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `no_generic_exception_throws` | ArchUnit | No code throws `Exception`, `Throwable` or `RuntimeException` directly | — | `GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS` | Implemented in `ArchitectureTest#noStandardStreamsLoggingOrGenericExceptions`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |

### Class Containment

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `configuration_classes_in_config_package` | ArchUnit | `@Configuration` classes live in a `..config..` package | — | `classes().that().areAnnotatedWith(Configuration.class).should().resideInAPackage("..config..").allowEmptyShould(true)` | **Currently violated by four classes:** `app.SecurityConfig`, `app.ClockConfig`, `access.SessionStoreConfig`, `adapters.oidc.MccClientConfig`. Kept as the catalogue defines it, per its never-weaken rule. Moving them is not free: `config` is a leaf module in `modulesDependOnlyDownwards`, and `SecurityConfig` needs `access` and `web`. **Needs a human decision before `/arch-tests-gen`** (see the summary). | — (not generated: needs a decision) |

### Dependency Injection

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_field_injection` | ArchUnit | Dependencies are constructor-injected | — | `GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION` | Implemented in `ArchitectureTest#noStandardStreamsLoggingOrGenericExceptions`. The catalogue prefers `noFields()...beAnnotatedWith(Autowired.class)` because the library rule also flags `@Value` fields; it passes today, so keep it unless a `@Value` field is ever needed. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |

### Code Quality

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_test_imports_in_production` | ArchUnit | Production code never imports a test framework | — | `noClasses().should().dependOnClassesThat().resideInAnyPackage("org.junit..", "org.mockito..", "org.testng..", "org.assertj..")` | Holds today (no match in main). Complements `fakes_never_ship_on_main`. | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `no_system_out` | ArchUnit | No `System.out` / `System.err`; logging goes through SLF4J | — | `GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS` | Implemented in `ArchitectureTest#noStandardStreamsLoggingOrGenericExceptions`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |

### ADR-derived and project rules

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `jdbcclient_only_in_stores` | ArchUnit | `JdbcClient` is used only inside `*Store` classes, so SQL stays in one place (ADR 0001, IM8 as-2) | — | `noClasses().that().haveSimpleNameNotEndingWith("Store").should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")` | **ADR 0001 says an architecture test does this; `ArchitectureTest` does not have it.** Holds today (five Stores, nothing else). Consider also `JdbcTemplate` and `NamedParameterJdbcTemplate`. | `backend/src/test/java/sg/annualva/arch/ModuleConventionsArchTest.java` |
| `scheduled_jobs_named_jobs` | ArchUnit | `@Scheduled` methods live in `*Job` classes (ADR 0012) | — | `methods().that().areAnnotatedWith(Scheduled.class).should().beDeclaredInClassesThat().haveSimpleNameEndingWith("Job").allowEmptyShould(true)` | Implemented in `ArchitectureTest#scheduledJobsAreNamedAsJobs`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `time_only_via_clock_bean` | ArchUnit | Time is read only through the `Clock` bean (ADR 0006, AGENTS.md) | — | `noClasses().that().resideOutsideOfPackage("sg.annualva.app").should().callMethod(Instant.class, "now")...` | Implemented in `ArchitectureTest#timeIsReadOnlyThroughTheClockBean`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `boundary_ports_implemented_only_by_adapters` | ArchUnit | Production implementations of `boundary` ports live under `adapters` | — | `noClasses().that().resideOutsideOfPackage("sg.annualva.adapters..").should().implement(IdentityProvider.class)` | Implemented in `ArchitectureTest#boundaryPortsAreImplementedOnlyByAdapters`. New ports add a row here. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `fakes_never_ship_on_main` | ArchUnit | Boundary fakes live only in the Seam 1 harness | — | `noClasses().should().haveSimpleNameContaining("Fake")...` | Implemented in `ArchitectureTest#fakesNeverShipOnMain`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `controller_methods_guarded` | ArchUnit | Every public controller method carries `@PreAuthorize` (deny by default, ac-1) | — | `methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class).and().arePublic().should().beAnnotatedWith(PreAuthorize.class)` | Implemented in `ArchitectureTest#everyControllerMethodIsEitherPublicByDesignOrGuarded`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `anonymous_endpoints_match_the_list` | ArchUnit | An endpoint says `permitAll()` exactly when its path is on `SecurityConfig.PUBLIC_PATHS` | — | Reflection test comparing `@PreAuthorize` with the path list | Implemented in `ArchitectureTest#anonymousEndpointsAreExactlyThoseOnTheAnonymousList`. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |
| `only_named_classes_write_role_grants` | ArchUnit | Roles change only through the role store, and no controller reaches it (story 76) | — | `noClasses().that().areNotAssignableTo(RoleGrantStore.class).and()...should().dependOnClassesThat().areAssignableTo(RoleGrantStore.class)` | Implemented in `ArchitectureTest#onlyNamedClassesWriteRoleGrants`. Add the role-holder review's writer to the exception list in its own ticket. | `backend/src/test/java/sg/annualva/arch/ArchitectureTest.java` |

---

## Backend — Feature Template

### Module Boundaries

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `module_not_dependent_on_composition_root_<feature>` | ArchUnit | A module never imports the composition root or a production adapter | — | `noClasses().that().resideInAPackage("sg.annualva.<feature>..").should().dependOnClassesThat().resideInAnyPackage("sg.annualva.app..", "sg.annualva.adapters..")` | Replace `<feature>` with the module's package name (`access`, `audit`, `cycle`, `register`, `submission`, `dashboard`, ...). Holds for every module today. | — |
| `module_depends_only_on_declared_modules_<feature>` | ArchUnit | A module depends only on the modules its `package-info.java` names | — | `noClasses().that().resideInAPackage("sg.annualva.<feature>..").should().dependOnClassesThat().resideInAnyPackage(<every sg.annualva module not named in the charter>)` | Fill the list from the charter, e.g. `submission` may use access, audit, cycle, register and web only. Holds today for all six domain modules. A module also needs its layer and arrow in `modulesDependOnlyDownwards`. | — |

---

## Frontend — Core

### Cycles

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_circular_module_deps` | depcruise | No import cycles anywhere in the module graph | `frontend/.dependency-cruiser.cjs` | `{ name: "no-circular", from: {}, to: { circular: true } }` | Implemented in `frontend/.dependency-cruiser.cjs` (`no-circular`). | `frontend/.dependency-cruiser.cjs` |

### ADR-derived and project rules

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_http_client_library` | depcruise | Plain `fetch` only, wrapped once in `src/api/client.ts` (ADR 0002) | `frontend/.dependency-cruiser.cjs` | `to: { dependencyTypes: ["npm"], path: "^(axios\|ky\|got\|superagent\|node-fetch\|@tanstack/react-query\|swr)$" }` | Implemented (`no-http-client-library`). | `frontend/.dependency-cruiser.cjs` |
| `no_router_or_ui_library` | depcruise | The lighter stack: no router, UI kit or CSS-in-JS library (ADR 0002) | `frontend/.dependency-cruiser.cjs` | `to: { dependencyTypes: ["npm"], path: "^(react-router\|@mui/\|...)" }` | Implemented (`no-router-or-ui-library`). | `frontend/.dependency-cruiser.cjs` |
| `api_is_the_only_network_layer` | depcruise | Only `src/api` and `src/app` touch the fetch wrapper | `frontend/.dependency-cruiser.cjs` | `from: { pathNot: "^src/(api\|app)/" }, to: { path: "^src/api/client\\.ts$" }` | Implemented (`api-is-the-only-network-layer`). Covers the catalogue's `no_direct_http_in_components`. | `frontend/.dependency-cruiser.cjs` |
| `components_are_pure` | depcruise | Shared components take props and never call the API or import screens | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/components/" }, to: { path: "^src/(api\|screens\|app)/", pathNot: "^src/api/problem\\.ts$" }` | Implemented (`components-are-pure`). | `frontend/.dependency-cruiser.cjs` |
| `no_cross_role_screen_imports` | depcruise | A role's screens never import another role's screens (only `shared`) | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/screens/([^/]+)/" }, to: { path: "^src/screens/([^/]+)/", pathNot: ["^src/screens/$1/", "^src/screens/shared/"] }` | Implemented (`no-cross-role-screen-imports`). The role-based form of `no_cross_page_imports` and `shared_no_feature_imports`. | `frontend/.dependency-cruiser.cjs` |
| `only_the_shell_mounts_screens` | depcruise | Only `src/app` mounts screens | `frontend/.dependency-cruiser.cjs` | `from: { pathNot: "^src/(app\|screens)/" }, to: { path: "^src/screens/" }` | Implemented (`only-the-shell-mounts-screens`). | `frontend/.dependency-cruiser.cjs` |
| `api_has_no_ui` | depcruise | The typed API modules import no UI code | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/api/" }, to: { path: "^src/(screens\|components\|app)/\|^node_modules/react-dom" }` | Implemented (`api-has-no-ui`). | `frontend/.dependency-cruiser.cjs` |
| `no_orphans` | depcruise | No unreachable module (dead code) | `frontend/.dependency-cruiser.cjs` | `from: { orphan: true, pathNot: [...d.ts] }` | Implemented (`no-orphans`). | `frontend/.dependency-cruiser.cjs` |
| `no_frontend_unit_test_libraries` | depcruise | No Vitest, Jest or React Testing Library in `src` (ADR 0009: business rules are proven at Seam 1, the browser only by thin Playwright scenarios) | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/" }, to: { dependencyTypes: ["npm", "npm-dev"], path: "^(vitest\|jest\|@testing-library/\|enzyme\|react-test-renderer)" }` | New. Holds today (none installed). | `frontend/.dependency-cruiser.cjs` |

---

## For Consideration

_These rules are not yet enforced. Review each category and move individual rows to the relevant section above when ready, then re-run `/arch-tests-gen`._

### Naming Conventions

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `exception_naming_convention` | ArchUnit | Classes extending `Exception` or `RuntimeException` end in `Exception` | — | `classes().that().areAssignableTo(Exception.class).should().haveSimpleNameEndingWith("Exception").allowEmptyShould(true)` | _Why deferred: low severity; the code already follows it (`PreviewNotStoredException`, `RejectedRequestException`, ...)._ | — |

### Code Quality

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `utility_classes_private_constructor` | ArchUnit | Static-only classes are final with a private constructor | — | `classes().that().haveSimpleNameEndingWith("Util").or()...should().haveOnlyPrivateConstructors().allowEmptyShould(true)` | _Why deferred: low severity; no `*Util` classes exist._ | — |
| `no_dependency_on_deprecated` | ArchUnit | No production class depends on a `@Deprecated` type | — | `noClasses().should().dependOnClassesThat().areAnnotatedWith(Deprecated.class).allowEmptyShould(true)` | _Why deferred: low severity; compiler deprecation warnings already show these._ | — |
| `no_new_inside_service` | ArchUnit | Services and stores are injected, never created with `new` | — | `noClasses().that().areTopLevelClasses().should().callConstructorWhere(target(owner(assignableTo(...Store))))` | _Why deferred: low severity; no instance seen._ | — |

### Module Boundaries

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `store_not_used_outside_module_<feature>` | ArchUnit | A module's `*Store` classes are used only inside that module | — | `classes().that().resideInAPackage("sg.annualva.<feature>..").and().haveSimpleNameEndingWith("Store").should().onlyBeAccessed().byAnyPackage("sg.annualva.<feature>..")` | _Why deferred: convention seen in code, not stated in an ADR._ `AuditStore`, `RegisterStore` and `SubmissionStore` are package-private; `access.AppUserStore` and `access.RoleGrantStore` are public by design, so those two would need an explicit exception. | — |

### Layer Semantics (LLM)

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `no_validation_in_stores` | LLM | `*Store` classes only persist; input validation lives in the service | `backend/src/main/java/sg/annualva/**/*Store.java` | Check that no `*Store` method validates input or applies a business rule | _Why deferred: LLM-reviewed, always For Consideration._ | — |
| `access_guard_on_every_system_path` | LLM | Every service path that addresses a System goes through `AccessGuard` (ADR 0013) | `backend/src/main/java/sg/annualva/{register,submission,dashboard}/*.java` | Check that each method taking a System id calls `AccessGuard` before reading or writing | _Why deferred: not structurally expressible. Tickets 11 and 12 follow it by hand; the reviewer checks it._ | — |
| `report_content_never_in_logs_audit_or_errors` | LLM | CVE ids, rule names, hosts and free text from a report never reach a log line, an audit entry or an error body (AGENTS.md) | `backend/src/main/java/**/*.java` | Pre-filter on `log.` and `ProblemResponses` calls; check what each interpolates | _Why deferred: LLM-reviewed. Becomes important when the parsers (tickets 13 to 17) land._ | — |

### Layer Boundaries (frontend)

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `hooks_no_screen_imports` | depcruise | Shared hooks never import screens or the app shell | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/hooks/" }, to: { path: "^src/(screens\|app)/" }` | _Why deferred: low severity; `components-are-pure` covers components but not `src/hooks`. One file today._ | — |
| `no_direct_http_in_components` | depcruise | UI components do not import HTTP libraries or `src/api` | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/(components\|screens)" }, to: { path: "^src/api/client\\.ts$" }` | _Why deferred: low severity; already covered by `api-is-the-only-network-layer` and `components-are-pure`._ | — |
| `styles_not_in_logic_files` | depcruise | Hooks, API and utility files import no CSS | `frontend/.dependency-cruiser.cjs` | `from: { path: "^src/(hooks\|api)/" }, to: { path: "\\.(css\|scss)$" }` | _Why deferred: low severity; `styles.css` is imported once, from `main.tsx`._ | — |

### Naming and Documentation (LLM, frontend)

| Rule | Tool | Intent | Target File | Sketch | Notes | Implemented In |
|---|---|---|---|---|---|---|
| `meaningful_public_api_names` | LLM | Exported functions name the domain action, not `process` or `handle` | `frontend/src/**/*.ts` | Pre-filter on `^export (function\|class)`; max 20 files | _Why deferred: LLM-reviewed, always For Consideration._ | — |
| `jsdoc_param_accuracy` | LLM | `@param` text describes the parameter's role, not its name | `frontend/src/**/*.ts` | Pre-filter on `@param`; max 20 files | _Why deferred: LLM-reviewed, always For Consideration._ | — |

---
_To promote a rule: copy the row into the relevant Enforced table above, then re-run `/arch-tests-gen`. No re-analysis needed._
_To re-run full analysis: `/arch-tests-plan`_
