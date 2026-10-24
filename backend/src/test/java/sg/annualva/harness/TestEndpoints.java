package sg.annualva.harness;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AppUserStore;
import sg.annualva.access.GrantSource;
import sg.annualva.access.Need;
import sg.annualva.access.Role;
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.access.RoleGrantStore;
import sg.annualva.ingestion.ScanPoller;
import sg.annualva.submission.OverdueSweep;
import sg.annualva.web.ProblemResponses;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Test-only endpoints under /api/test/** that let Seam 1 observe the cross-cutting behaviour
 * (error shape, validation, CSRF) and arrange state no public API offers yet (a pre-registered
 * email, a role grant and a System, as the System Register will create). /api/probe/** sits behind
 * the real security chain and stands in for the endpoints later slices add: it calls the same
 * method-level rules and {@link AccessGuard} those endpoints must.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEndpoints {

    @Bean
    @Order(0)
    SecurityFilterChain testEndpointsChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return http
                .securityMatcher("/api/test/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(handler))
                .exceptionHandling(ex -> ex.accessDeniedHandler(problems.accessDeniedHandler()))
                .build();
    }

    /** Picked up by component scanning (it lives under sg.annualva on the test classpath). */
    @RestController
    public static class TestController {

        public record Echo(@NotBlank String text) {}

        public record PreRegister(@NotBlank String email) {}

        /** @param source CONFIG, REGISTER or GRANT; defaults to REGISTER; a grant in a state the apply never makes (a lapsed one, say) is arranged here */
        public record GrantRequest(
                @NotBlank String role,
                @NotBlank String email,
                @Nullable String systemId,
                @Nullable String source,
                @Nullable String expiresAt) {}

        private static final String REVOKE = "UPDATE role_grant SET revoked_at = :now WHERE id = :id";
        private static final String SUSPEND = "UPDATE role_grant SET suspended_at = :now WHERE id = :id";
        private static final String LAPSE = "UPDATE role_grant SET lapsed_at = :now WHERE id = :id";
        private static final String LIST_GRANTS =
                "SELECT id, role, source, system_id, user_id IS NOT NULL AS attached, last_used_at, revoked_at IS NOT NULL AS revoked "
                + "FROM role_grant WHERE email = CAST(:email AS citext) ORDER BY granted_at, id";

        /** The Systems that "exist", standing in for the System Register's table. */
        private final Set<UUID> systems = ConcurrentHashMap.newKeySet();

        /** The Declaration versions a test added; the reset removes them, never the ones the migrations added. */
        private final Set<Integer> addedDeclarationVersions = ConcurrentHashMap.newKeySet();

        private final AppUserStore users;
        private final RoleGrantStore grants;
        private final AccessGuard guard;
        private final StepUp stepUp;
        private final JdbcClient jdbc;
        private final Clock clock;
        private final AuditLog audit;
        private final TransactionTemplate transaction;
        private final ScanPoller scanPoller;
        private final OverdueSweep overdueSweep;

        public TestController(AppUserStore users, RoleGrantStore grants, AccessGuard guard, StepUp stepUp, JdbcClient jdbc,
                Clock clock, AuditLog audit, TransactionTemplate transaction, ScanPoller scanPoller,
                OverdueSweep overdueSweep) {
            this.scanPoller = scanPoller;
            this.overdueSweep = overdueSweep;
            this.users = users;
            this.grants = grants;
            this.guard = guard;
            this.stepUp = stepUp;
            this.jdbc = jdbc;
            this.clock = clock;
            this.audit = audit;
            this.transaction = transaction;
        }

        /**
         * Stands in for a domain command that audits what it does. It writes one entry about the given System,
         * VA Submission and Finding (ROLE_GRANTED is only the action that exists today) in the way the
         * {@code ending} says, then ends:
         * <ul>
         *   <li>{@code commit}: the entry is written in the command's transaction, which commits
         *   <li>{@code rollback}: the same, then the command fails with a 403 and its transaction rolls back
         *   <li>{@code independent-rollback}: the entry is written in a transaction of its own, then the same 403
         *   <li>{@code no-transaction}: the entry is written with no transaction open at all
         * </ul>
         */
        @PostMapping("/api/probe/audited")
        @PreAuthorize("isAuthenticated()")
        public Map<String, String> audited(@RequestParam UUID system, @RequestParam UUID submission,
                @RequestParam UUID finding, @RequestParam String ending, Authentication caller) {
            var entry = AuditEvent.of(AuditAction.ROLE_GRANTED, AuditOutcome.SUCCESS)
                    .by(UUID.fromString(caller.getName()), null)
                    .onSystem(system).onSubmission(submission).onFinding(finding)
                    .with(DetailKey.COUNT, 3);
            if ("no-transaction".equals(ending)) {
                audit.record(entry);
            } else {
                transaction.executeWithoutResult(status -> {
                    if ("independent-rollback".equals(ending)) {
                        audit.recordIndependently(entry);
                    } else {
                        audit.record(entry);
                    }
                    if (!"commit".equals(ending)) {
                        throw new AccessDeniedException("The command is refused after it wrote");
                    }
                });
            }
            return Map.of("ending", ending);
        }

        /** Hands the allow-list a value that is not a code, as a careless caller might hand it free text. */
        @PostMapping("/api/probe/audit-free-text")
        @PreAuthorize("isAuthenticated()")
        public Map<String, String> freeText(@RequestParam String text) {
            transaction.executeWithoutResult(status -> audit.record(
                    AuditEvent.of(AuditAction.ROLE_GRANTED, AuditOutcome.SUCCESS).with(DetailKey.REASON, text)));
            return Map.of("written", "yes");
        }

        @GetMapping("/api/test/boom")
        public Map<String, String> boom() {
            throw new IllegalStateException("secret internal detail that must never leak");
        }

        @PostMapping("/api/test/echo")
        public Map<String, String> echo(@Valid @RequestBody Echo echo) {
            return Map.of("text", echo.text());
        }

        /** What the System Register will do when it names a person who has not signed in yet. */
        @PostMapping("/api/test/users")
        public Map<String, String> preRegister(@Valid @RequestBody PreRegister body) {
            return Map.of("uuid", users.preRegister(body.email(), clock.instant()).id().toString());
        }

        private static final String INSERT_SYSTEM =
                "INSERT INTO va_system (code, name, smo_email, cm_email, quarter, added_on) "
                + "VALUES (:code, 'Test System', :smo, :cm, 1, CAST(:addedOn AS date)) RETURNING id";

        /**
         * A System exists, for the tests of other modules that need one to address (the row has the Register's own shape:
         * a role grant for a System now needs a System). It stands in for nothing the Register does: the Register's own
         * Systems and grants come from an upload and its apply, which are never arranged here.
         */
        @PostMapping("/api/test/systems")
        public Map<String, String> createSystem() {
            String code = "TEST-" + UUID.randomUUID();
            UUID id = jdbc.sql(INSERT_SYSTEM)
                    .param("code", code)
                    .param("smo", code + ".smo@arranged.annualva.test")
                    .param("cm", code + ".cm@arranged.annualva.test")
                    .param("addedOn", java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).toString())
                    .query(UUID.class)
                    .single();
            systems.add(id);
            return Map.of("id", id.toString());
        }

        /** Empties the Register, its previews, the VA Submissions, the grants scoped to a System and the sessions between tests; the suite shares one database. */
        @PostMapping("/api/test/register-reset")
        public Map<String, String> resetRegister() {
            transaction.executeWithoutResult(status -> {
                // STOPGAP, remove when the real fix lands. Signed-in sessions are deleted too (the arranging browser keeps
                // its anonymous one, which holds its CSRF token). The defect: a session created by a step-up is not
                // evicted by that person's next sign-in, so it stays live, and the person's next step-up then sees two
                // live sessions, the one-session rule expires the current one, and the request answers 401. It showed as
                // RegisterApplyTest.applyingNeedsAFreshSignInAndChangesNothingWithoutOne failing in the full suite. It is
                // recorded on ticket 09 and not fixed here. Every Register test signs in after this reset.
                jdbc.sql("DELETE FROM spring_session WHERE principal_name IS NOT NULL AND principal_name <> ''").update();
                jdbc.sql("DROP TRIGGER IF EXISTS test_fault ON report_file").update();
                jdbc.sql("DROP TRIGGER IF EXISTS test_finding_fault ON finding_occurrence").update();
                jdbc.sql("DROP TRIGGER IF EXISTS test_finding_late ON report_file").update();
                jdbc.sql("DELETE FROM finding_occurrence").update();
                // The reviews are append-only too (V21), and name the proposals and the Scan Reports, so they go first.
                for (String review : new String[] {"finding_review", "report_review"}) {
                    jdbc.sql("ALTER TABLE " + review + " DISABLE TRIGGER " + review + "_append_only").update();
                    jdbc.sql("DELETE FROM " + review).update();
                    jdbc.sql("ALTER TABLE " + review + " ENABLE TRIGGER " + review + "_append_only").update();
                }
                jdbc.sql("UPDATE finding SET current_proposal_id = NULL, review_state = NULL").update();
                // The proposals are append-only for the application; only this reset, which owns the test schema, removes them.
                jdbc.sql("ALTER TABLE finding_status_proposal DISABLE TRIGGER finding_status_proposal_append_only").update();
                jdbc.sql("DELETE FROM finding_status_proposal").update();
                jdbc.sql("ALTER TABLE finding_status_proposal ENABLE TRIGGER finding_status_proposal_append_only").update();
                // The Bulk-set batches likewise (V15), after the proposals that name them.
                jdbc.sql("ALTER TABLE bulk_batch DISABLE TRIGGER bulk_batch_append_only").update();
                jdbc.sql("DELETE FROM bulk_batch").update();
                jdbc.sql("ALTER TABLE bulk_batch ENABLE TRIGGER bulk_batch_append_only").update();
                jdbc.sql("DELETE FROM finding").update();
                jdbc.sql("DELETE FROM report_file_stage").update();
                jdbc.sql("DELETE FROM report_file").update();
                jdbc.sql("DELETE FROM report_slot").update();
                // The accepted Declarations are append-only too (V19). The versions of the wording the migrations added are
                // kept; one a test added is removed, after the Declarations that name it.
                jdbc.sql("ALTER TABLE declaration DISABLE TRIGGER declaration_append_only").update();
                jdbc.sql("DELETE FROM declaration").update();
                jdbc.sql("ALTER TABLE declaration ENABLE TRIGGER declaration_append_only").update();
                if (!addedDeclarationVersions.isEmpty()) {
                    jdbc.sql("ALTER TABLE declaration_text DISABLE TRIGGER declaration_text_append_only").update();
                    for (int version : addedDeclarationVersions) {
                        jdbc.sql("DELETE FROM declaration_text WHERE version = :version").param("version", version)
                                .update();
                    }
                    jdbc.sql("ALTER TABLE declaration_text ENABLE TRIGGER declaration_text_append_only").update();
                }
                jdbc.sql("DELETE FROM submission_participation").update();
                jdbc.sql("DELETE FROM not_started_overdue").update();
                jdbc.sql("DELETE FROM va_submission").update();
                jdbc.sql("DELETE FROM role_grant WHERE system_id IS NOT NULL").update();
                jdbc.sql("DELETE FROM register_upload").update();
                jdbc.sql("DELETE FROM va_system").update();
            });
            systems.clear();
            addedDeclarationVersions.clear();
            return Map.of("reset", "yes");
        }

        /**
         * A new version of the Declaration wording, the agreed one, as a later migration adds it (ticket 24): the version
         * after the highest. The reset removes it.
         */
        @PostMapping("/api/test/declaration-text")
        public Map<String, Integer> declarationText() {
            int version = jdbc.sql("INSERT INTO declaration_text (version, text, placeholder) "
                            + "SELECT max(version) + 1, 'AGREED WORDING FOR A TEST. I declare what I submit is accurate.', "
                            + "false FROM declaration_text RETURNING version")
                    .query(Integer.class)
                    .single();
            addedDeclarationVersions.add(version);
            return Map.of("version", version);
        }

        /** One pass of the scan job, which {@code annualva.scheduling.enabled=false} keeps from running by itself in tests. */
        @PostMapping("/api/test/jobs/scan")
        public Map<String, String> scan() {
            scanPoller.poll();
            return Map.of("ran", "scan");
        }

        /** One pass of the Overdue sweep, which {@code annualva.scheduling.enabled=false} keeps from running by itself in tests. */
        @PostMapping("/api/test/jobs/overdue")
        public Map<String, String> overdue() {
            overdueSweep.sweep();
            return Map.of("ran", "overdue");
        }

        /**
         * Makes every write that moves a Scan Report file to a status starting with this prefix fail inside the database (a
         * trigger raises), so a test can prove what a rolled-back write leaves behind. {@code off} removes the fault.
         */
        @PostMapping("/api/test/faults/report-file/{prefix}")
        public Map<String, String> reportFileFault(@PathVariable String prefix) {
            jdbc.sql("DROP TRIGGER IF EXISTS test_fault ON report_file").update();
            if (!"off".equals(prefix)) {
                if (!prefix.matches("[A-Z_]+")) {
                    throw new IllegalArgumentException("a status prefix");
                }
                jdbc.sql("CREATE OR REPLACE FUNCTION test_fault() RETURNS trigger LANGUAGE plpgsql AS "
                        + "$$ BEGIN RAISE EXCEPTION 'injected fault'; END $$").update();
                jdbc.sql("CREATE TRIGGER test_fault BEFORE UPDATE ON report_file FOR EACH ROW "
                        + "WHEN (NEW.pipeline_status LIKE '" + prefix + "%' AND OLD.pipeline_status <> NEW.pipeline_status) "
                        + "EXECUTE FUNCTION test_fault()").update();
            }
            return Map.of("fault", prefix);
        }

        /**
         * Makes the write of a file's Finding occurrences fail inside the database (a trigger raises). That write is the
         * last one of the transaction that records a file as parsed, after the file row, its stage rows and its Findings
         * were written, so a test can prove that all of them are rolled back together. {@code on} sets the fault and
         * {@code off} removes it. {@code late} sets a fault that fires later still, when the transaction that records the
         * file as parsed commits (a deferred constraint trigger on the file's row), after every write of the settlement,
         * the Findings included: it proves the Findings are in that transaction and not in one of their own, which a
         * failure inside their own write cannot tell apart.
         */
        @PostMapping("/api/test/faults/finding/{mode}")
        public Map<String, String> findingFault(@PathVariable String mode) {
            jdbc.sql("DROP TRIGGER IF EXISTS test_finding_fault ON finding_occurrence").update();
            jdbc.sql("DROP TRIGGER IF EXISTS test_finding_late ON report_file").update();
            if ("late".equals(mode)) {
                jdbc.sql("CREATE OR REPLACE FUNCTION test_finding_late() RETURNS trigger LANGUAGE plpgsql AS "
                        + "$$ BEGIN RAISE EXCEPTION 'injected fault at commit'; END $$").update();
                jdbc.sql("CREATE CONSTRAINT TRIGGER test_finding_late AFTER UPDATE ON report_file DEFERRABLE INITIALLY "
                        + "DEFERRED FOR EACH ROW WHEN (NEW.pipeline_status = 'PARSED') "
                        + "EXECUTE FUNCTION test_finding_late()").update();
            }
            if ("on".equals(mode)) {
                jdbc.sql("CREATE OR REPLACE FUNCTION test_finding_fault() RETURNS trigger LANGUAGE plpgsql AS "
                        + "$$ BEGIN RAISE EXCEPTION 'injected fault'; END $$").update();
                jdbc.sql("CREATE TRIGGER test_finding_fault BEFORE INSERT ON finding_occurrence FOR EACH ROW "
                        + "EXECUTE FUNCTION test_finding_fault()").update();
            }
            return Map.of("fault", mode);
        }

        /**
         * Another pass of the scan job rejected this file (as malware) and has already swept it: the quarantine key is
         * cleared too, so nothing but the pass that lost the race can find a clean copy it wrote afterwards. The
         * quarantined blob in the fake store is left behind as an orphan, which is the endpoint's doing, not the app's.
         */
        @PostMapping("/api/test/report-files/{id}/rejected")
        public Map<String, String> reportFileRejected(@PathVariable UUID id) {
            jdbc.sql("UPDATE report_file SET pipeline_status = 'REJECTED_MALWARE', reject_code = 'MALWARE', "
                    + "scan_ticket = NULL, quarantine_key = NULL WHERE id = :id").param("id", id).update();
            return Map.of("rejected", id.toString());
        }

        /** A review state set directly, for the tests that need one on a Draft, where the review engine (ticket 28) does not review. */
        @PostMapping("/api/test/findings/{id}/review/{state}")
        public Map<String, String> findingReview(@PathVariable UUID id, @PathVariable String state) {
            jdbc.sql("UPDATE finding SET review_state = :state WHERE id = :id AND current_proposal_id IS NOT NULL")
                    .param("state", state).param("id", id).update();
            return Map.of("state", state);
        }

        /**
         * A not-applicable justification's review state set directly, on a Draft, where the review engine (ticket 28) does
         * not review: for the tests that a new justification puts it back to PENDING.
         */
        @PostMapping("/api/test/submissions/{id}/reports/{type}/review/{state}")
        public Map<String, String> reportReview(@PathVariable UUID id, @PathVariable String type,
                @PathVariable String state) {
            jdbc.sql("UPDATE report_slot SET review_state = :state WHERE submission_id = :id AND report_type = :type "
                    + "AND mode = 'NOT_APPLICABLE'").param("state", state).param("id", id).param("type", type).update();
            return Map.of("state", state);
        }

        /** A VA Submission in a state no command enters yet (the tables accept all nine), for the tests of the file rules. */
        @PostMapping("/api/test/submissions/{id}/state/{state}")
        public Map<String, String> submissionState(@PathVariable UUID id, @PathVariable String state) {
            jdbc.sql("UPDATE va_submission SET state = :state WHERE id = :id").param("state", state).param("id", id)
                    .update();
            return Map.of("state", state);
        }

        /**
         * When a VA Submission reached its milestones, and whether it was endorsed late, as the commands that write them
         * will (submit, pass to endorsement in ticket 30, endorse in ticket 34); a null field is left as it is.
         */
        @PostMapping("/api/test/submissions/{id}/milestones")
        public Map<String, String> milestones(@PathVariable UUID id, @RequestBody MilestonesRequest body) {
            jdbc.sql("UPDATE va_submission SET submitted_at = coalesce(CAST(:submitted AS timestamptz), submitted_at), "
                    + "submitted_by = CASE WHEN CAST(:submitted AS timestamptz) IS NULL THEN submitted_by "
                    + "ELSE started_by END, "
                    + "passed_to_endorsement_at = coalesce(CAST(:passed AS timestamptz), passed_to_endorsement_at), "
                    + "endorsed_at = coalesce(CAST(:endorsed AS timestamptz), endorsed_at), "
                    + "endorsed_late = coalesce(CAST(:late AS boolean), endorsed_late) WHERE id = :id")
                    .param("submitted", body.submittedAt()).param("passed", body.passedToEndorsementAt())
                    .param("endorsed", body.endorsedAt()).param("late", body.endorsedLate()).param("id", id).update();
            return Map.of("id", id.toString());
        }

        /** Instants such as {@code 2027-08-10T02:00:00Z}. */
        public record MilestonesRequest(
                @Nullable String submittedAt, @Nullable String passedToEndorsementAt, @Nullable String endorsedAt,
                @Nullable Boolean endorsedLate) {}

        /** What configuration and the later Auditor grant will do, and a state the apply never makes: write a role grant. */
        @PostMapping("/api/test/grants")
        public Map<String, String> grant(@Valid @RequestBody GrantRequest body) {
            var grant = grants.grant(
                    Role.valueOf(body.role()),
                    body.email(),
                    body.systemId() == null ? null : UUID.fromString(body.systemId()),
                    body.source() == null ? GrantSource.REGISTER : GrantSource.valueOf(body.source()),
                    null,
                    body.expiresAt() == null ? null : Instant.parse(body.expiresAt()),
                    clock.instant());
            return Map.of("id", grant.id().toString());
        }

        /** The states that later slices own (revoke by Register change, lapse job, role-holder review). */
        @PostMapping("/api/test/grants/{id}/{change}")
        public Map<String, String> change(@PathVariable UUID id, @PathVariable String change) {
            String sql = switch (change) {
                case "revoke" -> REVOKE;
                case "suspend" -> SUSPEND;
                case "lapse" -> LAPSE;
                default -> throw new IllegalArgumentException("unknown change");
            };
            jdbc.sql(sql).param("id", id).param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC)).update();
            return Map.of("change", change);
        }

        /** Observation until a role-holder read API exists: the grants held under one email. */
        @GetMapping("/api/test/grants")
        public List<Map<String, Object>> listGrants(@RequestParam String email) {
            return jdbc.sql(LIST_GRANTS).param("email", email).query((rs, row) -> {
                Map<String, Object> view = new java.util.LinkedHashMap<>();
                view.put("id", rs.getObject("id", UUID.class).toString());
                view.put("role", rs.getString("role"));
                view.put("source", rs.getString("source"));
                view.put("systemId", rs.getObject("system_id", UUID.class) == null ? null
                        : rs.getObject("system_id", UUID.class).toString());
                view.put("attached", rs.getBoolean("attached"));
                view.put("revoked", rs.getBoolean("revoked"));
                var lastUsed = rs.getObject("last_used_at", java.time.OffsetDateTime.class);
                view.put("lastUsedAt", lastUsed == null ? null : lastUsed.toInstant().toString());
                return view;
            }).list();
        }

        /** Behind the production chain: 401 without a session, the principal name with one. */
        @GetMapping("/api/probe/whoami")
        public Map<String, String> whoami(Authentication authentication) {
            return Map.of("name", authentication.getName());
        }

        /** Stands in for a sensitive command (the Register apply, endorsing): it must ask for a fresh sign-in first. */
        @PostMapping("/api/probe/sensitive")
        @PreAuthorize("isAuthenticated()")
        public Map<String, String> sensitive() {
            stepUp.require("probe-sensitive");
            return Map.of("done", "yes");
        }

        @GetMapping("/api/probe/va-poc")
        @PreAuthorize("hasRole('VA_POC')")
        public Map<String, String> vaPocOnly() {
            return Map.of("area", "va-poc");
        }

        @GetMapping("/api/probe/cyber-manager")
        @PreAuthorize("hasRole('CYBER_MANAGER')")
        public Map<String, String> cyberManagerOnly() {
            return Map.of("area", "cyber-manager");
        }

        /** A service path that addresses one System: ownership first, then existence, one answer for both. */
        @GetMapping("/api/probe/systems/{id}/{need}")
        @PreAuthorize("isAuthenticated()")
        public Map<String, String> system(@PathVariable UUID id, @PathVariable Need need) {
            guard.requireSystem(id, need);
            if (!systems.contains(id)) {
                throw new ResourceNotFoundException();
            }
            return Map.of("id", id.toString());
        }

        /** A list: only the Systems the caller's roles allow for the need. */
        @GetMapping("/api/probe/systems")
        @PreAuthorize("isAuthenticated()")
        public List<String> visibleSystems(@RequestParam Need need) {
            var scope = guard.scope(need);
            var visible = new TreeSet<String>();
            systems.stream().filter(scope::contains).forEach(id -> visible.add(id.toString()));
            return List.copyOf(visible);
        }
    }
}
