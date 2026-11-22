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
import sg.annualva.export.ExportWorker;
import sg.annualva.ingestion.ScanPoller;
import sg.annualva.notification.DeadlineReminders;
import sg.annualva.notification.MailOutbox;
import sg.annualva.retention.DisposalExecutor;
import sg.annualva.retention.DisposalPlanner;
import sg.annualva.submission.CycleLifecycle;
import sg.annualva.submission.ExemptionLapse;
import sg.annualva.submission.OverdueSweep;
import sg.annualva.submission.RoleLapses;
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
        private final MailOutbox mailOutbox;
        private final CycleLifecycle cycleLifecycle;
        private final ExportWorker exportWorker;
        private final ExemptionLapse exemptionLapse;
        private final DisposalExecutor disposalExecutor;
        private final DisposalPlanner disposalPlanner;
        private final DeadlineReminders reminders;
        private final RoleLapses roleLapses;

        public TestController(AppUserStore users, RoleGrantStore grants, AccessGuard guard, StepUp stepUp, JdbcClient jdbc,
                Clock clock, AuditLog audit, TransactionTemplate transaction, ScanPoller scanPoller,
                OverdueSweep overdueSweep, MailOutbox mailOutbox, CycleLifecycle cycleLifecycle,
                ExportWorker exportWorker, ExemptionLapse exemptionLapse, DisposalExecutor disposalExecutor,
                DisposalPlanner disposalPlanner, DeadlineReminders reminders, RoleLapses roleLapses) {
            this.roleLapses = roleLapses;
            this.exemptionLapse = exemptionLapse;
            this.disposalExecutor = disposalExecutor;
            this.disposalPlanner = disposalPlanner;
            this.reminders = reminders;
            this.cycleLifecycle = cycleLifecycle;
            this.scanPoller = scanPoller;
            this.overdueSweep = overdueSweep;
            this.mailOutbox = mailOutbox;
            this.exportWorker = exportWorker;
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

        /**
         * Empties the Register, its previews, the VA Submissions and the grants scoped to a System between tests; the suite
         * shares one database. Signed-in sessions are left alone: the one-session rule ends them as people sign in again
         * (ticket 50 removed the stopgap that deleted them, ADR 0057).
         */
        @PostMapping("/api/test/register-reset")
        public Map<String, String> resetRegister() {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("DROP TRIGGER IF EXISTS test_fault ON report_file").update();
                jdbc.sql("DROP TRIGGER IF EXISTS test_finding_fault ON finding_occurrence").update();
                jdbc.sql("DROP TRIGGER IF EXISTS test_finding_late ON report_file").update();
                // The notifications (V27) name the Systems and the VA Submissions, so they go before both.
                jdbc.sql("DROP TRIGGER IF EXISTS test_notification_fault ON notification").update();
                // The mail outbox (V28) names the notifications, so it goes before them.
                jdbc.sql("DELETE FROM mail_outbox").update();
                jdbc.sql("DELETE FROM notification").update();
                // The Exemption requests (V33) name the VA Submissions and their evidence files.
                jdbc.sql("DELETE FROM exemption").update();
                jdbc.sql("DELETE FROM finding_occurrence").update();
                // The returns (V23) name the reviews, and the reviews (V21, V22) name the proposals, the batches and the
                // Scan Reports, so they go first, a return before the reviews it names and a Finding's review before the
                // batch review it names; all are append-only.
                for (String table : new String[] {"submission_return_item", "submission_return", "finding_review",
                        "batch_review", "report_review"}) {
                    emptyHistory(table);
                }
                jdbc.sql("UPDATE finding SET current_proposal_id = NULL, review_state = NULL").update();
                // The proposals are append-only for the application; only this reset, which owns the test schema, removes them.
                emptyHistory("finding_status_proposal");
                // The Bulk-set batches likewise (V15), after the proposals that name them.
                emptyHistory("bulk_batch");
                jdbc.sql("DELETE FROM finding").update();
                jdbc.sql("DELETE FROM report_file_stage").update();
                jdbc.sql("DELETE FROM report_file").update();
                jdbc.sql("DELETE FROM report_slot").update();
                // The accepted Declarations are append-only too (V19). The versions of the wording the migrations added are
                // kept; one a test added, or a VA POC published through the API (ticket 61), is removed, after the
                // Declarations that name it.
                emptyHistory("declaration");
                jdbc.sql("ALTER TABLE declaration_text DISABLE TRIGGER declaration_text_append_only").update();
                for (int version : addedDeclarationVersions) {
                    jdbc.sql("DELETE FROM declaration_text WHERE version = :version").param("version", version)
                            .update();
                }
                jdbc.sql("DELETE FROM declaration_text WHERE published_by IS NOT NULL").update();
                jdbc.sql("ALTER TABLE declaration_text ENABLE TRIGGER declaration_text_append_only").update();
                jdbc.sql("DELETE FROM submission_participation").update();
                jdbc.sql("DELETE FROM not_started_overdue").update();
                // The VA Cycle lifecycle's records (V30): what the opening kept of the Register, and the opens and closes.
                jdbc.sql("DELETE FROM cycle_register_system").update();
                jdbc.sql("DELETE FROM cycle_register").update();
                jdbc.sql("DELETE FROM va_cycle").update();
                jdbc.sql("DELETE FROM va_submission").update();
                jdbc.sql("DELETE FROM role_grant WHERE system_id IS NOT NULL").update();
                // The organisation-wide grants (the VA POCs') stay between tests, so the role lapse (ticket 46) starts again
                // from their grant: one a test lapsed stands again, and a use or return at a later test's clock is forgotten.
                jdbc.sql("UPDATE role_grant SET lapsed_at = NULL, last_used_at = NULL, returned_at = NULL, "
                        + "return_trigger = NULL WHERE system_id IS NULL").update();
                // Likewise a VA POC's grant a test suspended (ticket 47), the identity provider's word that someone is
                // disabled, and the role-holder reviews recorded.
                jdbc.sql("UPDATE role_grant SET suspended_at = NULL, suspended_by = NULL, suspension_cause = NULL "
                        + "WHERE source = 'CONFIG'").update();
                jdbc.sql("UPDATE app_user SET idp_disabled_at = NULL WHERE idp_disabled_at IS NOT NULL").update();
                jdbc.sql("DELETE FROM role_holder_review").update();
                jdbc.sql("DELETE FROM register_upload").update();
                jdbc.sql("DELETE FROM export_request").update();
                // The disposal lists (V36) name no other row by key; at most one may wait at a time. An item is only ever
                // inserted (V45), so the items go with their triggers switched off, like the history.
                emptyHistory("disposal_item");
                jdbc.sql("DELETE FROM disposal_list").update();
                jdbc.sql("DELETE FROM va_system").update();
            });
            systems.clear();
            addedDeclarationVersions.clear();
            return Map.of("reset", "yes");
        }

        /**
         * Removes every row of an append-only history table with its own triggers switched off: the one that refuses the
         * statement and the one that refuses each row no approved disposal list names. {@code USER} leaves the foreign
         * keys' internal triggers on. Call it only inside the reset's transaction: the triggers are off from the
         * {@code DISABLE} to the {@code ENABLE}, under the table lock the {@code ALTER TABLE} holds until the
         * transaction ends, and a failure in between rolls the {@code DISABLE} back. Only the reset, which owns the test
         * schema, does this.
         */
        private void emptyHistory(String table) {
            jdbc.sql("ALTER TABLE " + table + " DISABLE TRIGGER USER").update();
            jdbc.sql("DELETE FROM " + table).update();
            jdbc.sql("ALTER TABLE " + table + " ENABLE TRIGGER USER").update();
        }

        /**
         * A new version of the Declaration wording, the agreed one (ticket 24), as a VA POC publishes it (ticket 61) but
         * with no publisher: the version after the highest. The reset removes it.
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

        /** One pass of the deadline reminder planner, which {@code annualva.scheduling.enabled=false} keeps from running by itself in tests. */
        @PostMapping("/api/test/jobs/reminders")
        public Map<String, String> reminders() {
            reminders.plan();
            return Map.of("ran", "reminders");
        }

        /** One pass of the mail dispatcher, which {@code annualva.scheduling.enabled=false} keeps from running by itself in tests. */
        @PostMapping("/api/test/jobs/mail")
        public Map<String, String> mail() {
            try {
                mailOutbox.dispatch();
            } catch (CapturingMailRelay.DispatcherStopped stopped) {
                return Map.of("ran", "stopped");
            }
            return Map.of("ran", "mail");
        }

        private static final String OUTBOX =
                "SELECT o.id, u.email::text AS recipient, n.action, o.template, o.status, o.attempts, o.next_attempt_at, "
                + "o.relay_message_id FROM mail_outbox o JOIN notification n ON n.id = o.notification_id "
                + "JOIN app_user u ON u.id = n.recipient_id ORDER BY o.created_at, n.action, u.email";

        /** Observation until a delivery read exists (ticket 39): every queued mail, oldest first, as the outbox holds it. */
        @GetMapping("/api/test/mail-outbox")
        public List<Map<String, Object>> outbox() {
            return jdbc.sql(OUTBOX).query((rs, row) -> {
                Map<String, Object> view = new java.util.LinkedHashMap<>();
                view.put("id", rs.getObject("id", UUID.class).toString());
                view.put("recipient", rs.getString("recipient"));
                view.put("action", rs.getString("action"));
                view.put("template", rs.getString("template"));
                view.put("status", rs.getString("status"));
                view.put("attempts", rs.getInt("attempts"));
                view.put("nextAttemptAt",
                        rs.getObject("next_attempt_at", java.time.OffsetDateTime.class).toInstant().toString());
                view.put("relayMessageId", rs.getString("relay_message_id"));
                return view;
            }).list();
        }

        /**
         * A System's name or System ID ({@code name}, {@code code}; a missing one is kept) changed behind the Register's
         * back to one the Register would refuse (a format character, say), so a test can prove the mail allow-list
         * refuses whatever reaches it, whoever wrote it.
         */
        @PostMapping("/api/test/systems/{id}/name")
        public Map<String, String> renameSystem(@PathVariable UUID id, @RequestBody Map<String, String> body) {
            jdbc.sql("UPDATE va_system SET name = coalesce(:name, name), code = coalesce(CAST(:code AS citext), code) "
                    + "WHERE id = :id").param("name", body.get("name")).param("code", body.get("code")).param("id", id)
                    .update();
            return Map.of("id", id.toString());
        }

        /** One pass of the VA Cycle lifecycle job, which {@code annualva.scheduling.enabled=false} keeps from running by itself in tests. */
        @PostMapping("/api/test/jobs/cycle-lifecycle")
        public Map<String, String> cycleLifecycle() {
            cycleLifecycle.run();
            return Map.of("ran", "cycle-lifecycle");
        }

        /** One pass of the conditional Exemption lapse job (ticket 42), which scheduling being off keeps from running. */
        @PostMapping("/api/test/jobs/exemption-lapse")
        public Map<String, String> exemptionLapse() {
            exemptionLapse.run();
            return Map.of("ran", "exemption-lapse");
        }

        /** One pass of the role lapse job (ticket 46), which scheduling being off keeps from running. */
        @PostMapping("/api/test/jobs/role-lapse")
        public Map<String, String> roleLapse() {
            roleLapses.run();
            return Map.of("ran", "role-lapse");
        }

        /** One pass of the export job, which {@code annualva.scheduling.enabled=false} keeps from running by itself in tests. */
        @PostMapping("/api/test/jobs/exports")
        public Map<String, String> exports() {
            exportWorker.run();
            return Map.of("ran", "exports");
        }

        /**
         * One pass of the retention job, which {@code annualva.scheduling.enabled=false} keeps from running by itself in
         * tests: the executor disposes of an approved list, then the listing writes what is due.
         */
        @PostMapping("/api/test/jobs/retention")
        public Map<String, String> retention() {
            disposalExecutor.execute();
            disposalPlanner.plan();
            return Map.of("ran", "retention");
        }

        /**
         * Observation of what a disposal leaves in the database: how many rows every table of the schema holds, by name.
         * The suite resets the domain tables before every test, so a test that disposes of everything it made expects the
         * counts it can name. There is no read of the append-only history tables (the proposals, batches, reviews and
         * returns) once their VA Submission is gone, so only the tables themselves can show that they are empty.
         */
        @GetMapping("/api/test/rows")
        public Map<String, Long> rows() {
            Map<String, Long> rows = new java.util.TreeMap<>();
            for (String table : jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = "
                    + "current_schema() AND table_type = 'BASE TABLE'").query(String.class).list()) {
                rows.put(table, jdbc.sql("SELECT count(*) FROM \"" + table + "\"").query(Long.class).single());
            }
            return rows;
        }

        /** Makes what a queued export will hold unreadable, as a row written before its shape changed would be. */
        @PostMapping("/api/test/exports/{id}/unreadable")
        public Map<String, String> unreadableExport(@PathVariable UUID id) {
            jdbc.sql("UPDATE export_request SET content = '{\"cycle\": \"not a year\"}'::jsonb WHERE id = :id")
                    .param("id", id).update();
            return Map.of("unreadable", id.toString());
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
        /**
         * Makes every write of a notification fail inside the database ("on"), so a test can prove that a notification
         * that cannot be written leaves the committed move it reports as it is (ADR 0043 item 1); "off" removes it.
         */
        @PostMapping("/api/test/faults/notification/{mode}")
        public Map<String, String> notificationFault(@PathVariable String mode) {
            jdbc.sql("DROP TRIGGER IF EXISTS test_notification_fault ON notification").update();
            if ("on".equals(mode)) {
                jdbc.sql("CREATE OR REPLACE FUNCTION test_notification_fault() RETURNS trigger LANGUAGE plpgsql AS "
                        + "$$ BEGIN RAISE EXCEPTION 'injected fault'; END $$").update();
                jdbc.sql("CREATE TRIGGER test_notification_fault BEFORE INSERT ON notification FOR EACH ROW "
                        + "EXECUTE FUNCTION test_notification_fault()").update();
            }
            return Map.of("fault", mode);
        }

        /**
         * Makes an audit write fail inside the database, so a test can prove that an audit entry that cannot be written is
         * counted. "on": the insert of an entry of the probe's action ({@code ROLE_GRANTED}) fails, in the action's
         * transaction. "commit": an entry of a refused request ({@code REQUEST_REFUSED}) is inserted and its own transaction
         * fails at the commit (a deferred constraint trigger), which is a failure outside the insert. "off" removes both.
         * Other actions are untouched, so signing in and the scrape itself still work while one is on.
         */
        @PostMapping("/api/test/faults/audit/{mode}")
        public Map<String, String> auditFault(@PathVariable String mode) {
            jdbc.sql("DROP TRIGGER IF EXISTS test_audit_fault ON audit_entry").update();
            jdbc.sql("DROP TRIGGER IF EXISTS test_audit_commit_fault ON audit_entry").update();
            if ("off".equals(mode)) {
                return Map.of("fault", mode);
            }
            jdbc.sql("CREATE OR REPLACE FUNCTION test_audit_fault() RETURNS trigger LANGUAGE plpgsql AS "
                    + "$$ BEGIN RAISE EXCEPTION 'injected fault'; END $$").update();
            if ("on".equals(mode)) {
                jdbc.sql("CREATE TRIGGER test_audit_fault BEFORE INSERT ON audit_entry FOR EACH ROW "
                        + "WHEN (NEW.action = 'ROLE_GRANTED') EXECUTE FUNCTION test_audit_fault()").update();
            } else if ("commit".equals(mode)) {
                jdbc.sql("CREATE CONSTRAINT TRIGGER test_audit_commit_fault AFTER INSERT ON audit_entry "
                        + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.action = 'REQUEST_REFUSED') "
                        + "EXECUTE FUNCTION test_audit_fault()").update();
            }
            return Map.of("fault", mode);
        }

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

        /**
         * Stands every in-flight VA Submission (not in a final state) aside as Endorsed and answers the state each was in,
         * by id, so a test can apply a Register change that Release 1 blocks or withdraws (ADR 0049) and put them back.
         */
        @PostMapping("/api/test/submissions/stand-aside")
        public Map<String, String> standAside() {
            Map<String, String> states = new java.util.LinkedHashMap<>();
            jdbc.sql("UPDATE va_submission s SET state = 'ENDORSED' FROM (SELECT id, state FROM va_submission "
                    + "WHERE state = ANY(CAST(:inFlight AS text[])) FOR UPDATE) old WHERE s.id = old.id "
                    + "RETURNING s.id, old.state")
                    .param("inFlight", java.util.Arrays.stream(sg.annualva.submission.SubmissionState.values())
                            .filter(sg.annualva.submission.SubmissionState::inFlight).map(Enum::name)
                            .toArray(String[]::new))
                    .query(rs -> {
                        states.put(rs.getString("id"), rs.getString("state"));
                    });
            return states;
        }

        /** A VA Submission in a state no command enters yet (the tables accept all nine), for the tests of the file rules. */
        @PostMapping("/api/test/submissions/{id}/state/{state}")
        public Map<String, String> submissionState(@PathVariable UUID id, @PathVariable String state) {
            jdbc.sql("UPDATE va_submission SET state = :state WHERE id = :id").param("state", state).param("id", id)
                    .update();
            return Map.of("state", state);
        }

        /**
         * When a VA Submission reached its milestones, and whether it was endorsed late, set directly, for a read-model
         * test that needs dates the commands (submit, pass to endorsement, endorse) would only write on the clock's day;
         * a null field is left as it is.
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

        /**
         * A revoked or lapsed grant in one step, for a test about what such a grant gives (a suspension goes through the
         * real {@code POST /api/role-holders/{id}/suspend}).
         */
        @PostMapping("/api/test/grants/{id}/{change}")
        public Map<String, String> change(@PathVariable UUID id, @PathVariable String change) {
            String sql = switch (change) {
                case "revoke" -> REVOKE;
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
