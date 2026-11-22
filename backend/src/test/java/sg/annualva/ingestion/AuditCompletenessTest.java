package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import sg.annualva.audit.AuditAction;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import sg.annualva.harness.FakeMalwareScanner;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.Samples;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 50, story 84 and IM8 lm-4: every audited action, walked once through the HTTP API in one VA Cycle's life
 * (Register, a VA Submission from start to endorsement through both returns, Exemptions approved, rejected and confirmed,
 * a withdrawal, role changes, an export, a disposal, the Overdue sweep, the role lapse and the Cycle's close), and then
 * one dynamic test per row of {@link #ROWS} asserting its entry: the action and outcome, the actor and the role they held
 * (none for a job), the System, VA Submission and Finding ids where it is about one, the fixed detail it must carry, and
 * never any free text. Every person's free text carries {@link #SENTINEL}, which no entry may hold.
 */
class AuditCompletenessTest extends ExemptionSeam1 {

    static final String SENTINEL = "SENTINEL-T50-FREE-TEXT";

    /** The story-84 list, plus lm-4's sign-in, step-up and refusals. */
    enum Kind { VIEW, DOWNLOAD, UPLOAD, STATUS_CHANGE, CONFIRMATION, DISPUTE, RETURN, ENDORSEMENT, EXEMPTION_DECISION,
        REGISTER_CHANGE, ROLE_CHANGE, EXPORT, DISPOSAL, ACCESS }

    /** Which ids an entry must carry. */
    enum About { NOTHING, SYSTEM, SUBMISSION, FINDING }

    /**
     * One audited action as the walk makes it: its outcome, who (a person of the walk, or null for a job), the role they
     * act in, what the entry is about and the detail it must carry.
     */
    record Row(Kind kind, AuditAction action, String outcome, @Nullable String actor, @Nullable String role, About about,
            Map<String, String> detail) {

        @Override
        public String toString() {
            return kind + " " + action + " " + outcome + (detail.isEmpty() ? "" : " " + detail) + " by "
                    + (actor == null ? "a job" : actor);
        }
    }

    static final String SMO = "SMO";
    static final String CYBER_MANAGER = "CYBER_MANAGER";
    static final String VA_POC = "VA_POC";
    static final String POC_1 = "POC_1";
    static final String POC_2 = "POC_2";

    /** An action that succeeded. */
    static Row row(Kind kind, AuditAction action, @Nullable String actor, @Nullable String role, About about,
            String... detail) {
        return new Row(kind, action, "SUCCESS", actor, role, about, pairs(detail));
    }

    /** An action the application refused, which is audited as much as one it did (IM8 lm-4). */
    static Row refusal(Kind kind, AuditAction action, @Nullable String actor, @Nullable String role, About about,
            String... detail) {
        return new Row(kind, action, "REFUSED", actor, role, about, pairs(detail));
    }

    private static Map<String, String> pairs(String... detail) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (int i = 0; i < detail.length; i += 2) {
            pairs.put(detail[i], detail[i + 1]);
        }
        return pairs;
    }

    static final List<Row> ROWS = List.of(
            row(Kind.VIEW, AuditAction.SUBMISSION_VIEWED, CM, CYBER_MANAGER, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.FINDINGS_VIEWED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.FILE_VIEWED, CM, CYBER_MANAGER, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.FILE_METADATA_VIEWED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.FINDING_PROPOSALS_VIEWED, CM, CYBER_MANAGER, About.FINDING),
            row(Kind.VIEW, AuditAction.BATCHES_VIEWED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.READINESS_VIEWED, CM, CYBER_MANAGER, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.SCAN_REPORTS_VIEWED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.SUBMISSION_RETURNS_VIEWED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.REWORK_QUEUE_VIEWED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.VIEW, AuditAction.EXEMPTIONS_VIEWED, POC_2, VA_POC, About.NOTHING),
            row(Kind.VIEW, AuditAction.REGISTER_PREVIEW_VIEWED, POC_1, VA_POC, About.NOTHING),
            row(Kind.VIEW, AuditAction.AUDIT_LOG_VIEWED, CM, CYBER_MANAGER, About.NOTHING, "page", "0"),
            row(Kind.VIEW, AuditAction.AUDIT_LOG_VIEWED, POC_1, VA_POC, About.NOTHING, "filter_action",
                    "SUBMISSION_ENDORSED"),
            row(Kind.DOWNLOAD, AuditAction.FILE_DOWNLOADED, CM, CYBER_MANAGER, About.SUBMISSION),
            row(Kind.DOWNLOAD, AuditAction.REGISTER_TEMPLATE_DOWNLOADED, POC_1, VA_POC, About.NOTHING),
            row(Kind.DOWNLOAD, AuditAction.EXPORT_DOWNLOADED, POC_1, VA_POC, About.NOTHING),
            row(Kind.UPLOAD, AuditAction.FILE_UPLOADED, SMO_A, SMO, About.SUBMISSION),
            refusal(Kind.UPLOAD, AuditAction.FILE_REJECTED, null, null, About.SUBMISSION, "reject_code", "malware"),
            row(Kind.UPLOAD, AuditAction.REGISTER_PREVIEWED, POC_1, VA_POC, About.NOTHING),
            refusal(Kind.UPLOAD, AuditAction.REGISTER_UPLOAD_REJECTED, POC_1, VA_POC, About.NOTHING, "reject_code", "not-xlsx"),
            row(Kind.STATUS_CHANGE, AuditAction.SUBMISSION_STARTED, SMO_A, SMO, About.SUBMISSION, "state_to", "DRAFT"),
            row(Kind.STATUS_CHANGE, AuditAction.SUBMISSION_SUBMITTED, SMO_A, SMO, About.SUBMISSION, "state_to", "IN_REVIEW"),
            row(Kind.STATUS_CHANGE, AuditAction.SUBMISSION_PASSED, CM, CYBER_MANAGER, About.SUBMISSION,
                    "state_to", "AWAITING_ENDORSEMENT"),
            row(Kind.STATUS_CHANGE, AuditAction.STATUS_PROPOSED, SMO_A, SMO, About.FINDING, "finding_status", "FALSE_POSITIVE"),
            row(Kind.STATUS_CHANGE, AuditAction.BATCH_PROPOSED, SMO_A, SMO, About.SUBMISSION, "finding_status", "FALSE_POSITIVE"),
            row(Kind.STATUS_CHANGE, AuditAction.REPORT_MODE_SET, SMO_A, SMO, About.SUBMISSION, "mode", "NOT_APPLICABLE"),
            row(Kind.STATUS_CHANGE, AuditAction.FINDING_ENTERED, SMO_A, SMO, About.FINDING),
            row(Kind.STATUS_CHANGE, AuditAction.FILE_METADATA_CONFIRMED, SMO_A, SMO, About.SUBMISSION),
            row(Kind.STATUS_CHANGE, AuditAction.SUBMISSION_OVERDUE, null, null, About.SYSTEM, "state", "NOT_STARTED"),
            row(Kind.STATUS_CHANGE, AuditAction.SUBMISSION_WITHDRAWN, POC_1, VA_POC, About.SUBMISSION, "state_to", "WITHDRAWN"),
            row(Kind.STATUS_CHANGE, AuditAction.SUBMISSION_NOT_ENDORSED, POC_1, VA_POC, About.SUBMISSION,
                    "state_from", "NOT_STARTED", "state_to", "NOT_ENDORSED"),
            row(Kind.STATUS_CHANGE, AuditAction.CYCLE_OPENED, null, null, About.NOTHING),
            row(Kind.STATUS_CHANGE, AuditAction.CYCLE_CLOSED, POC_1, VA_POC, About.NOTHING, "cycle", "2027"),
            row(Kind.CONFIRMATION, AuditAction.FINDING_REVIEWED, CM, CYBER_MANAGER, About.FINDING, "decision", "CONFIRMED"),
            row(Kind.CONFIRMATION, AuditAction.REPORT_REVIEWED, CM, CYBER_MANAGER, About.SUBMISSION, "decision", "CONFIRMED"),
            row(Kind.CONFIRMATION, AuditAction.BATCH_REVIEWED, CM, CYBER_MANAGER, About.SUBMISSION, "decision", "CONFIRMED"),
            row(Kind.CONFIRMATION, AuditAction.EXEMPTION_VA_RECORDED, SMO_B, SMO, About.SUBMISSION),
            row(Kind.CONFIRMATION, AuditAction.EXEMPTION_VA_CONFIRMED, POC_1, VA_POC, About.SUBMISSION),
            row(Kind.DISPUTE, AuditAction.FINDING_REVIEWED, CM, CYBER_MANAGER, About.FINDING, "decision", "DISPUTED"),
            row(Kind.RETURN, AuditAction.SUBMISSION_RETURNED, CM, CYBER_MANAGER, About.SUBMISSION,
                    "state_from", "IN_REVIEW", "state_to", "RETURNED_FOR_REWORK"),
            row(Kind.RETURN, AuditAction.SUBMISSION_RETURNED, POC_1, VA_POC, About.SUBMISSION,
                    "state_from", "AWAITING_ENDORSEMENT", "state_to", "RETURNED_FOR_REWORK"),
            row(Kind.ENDORSEMENT, AuditAction.SUBMISSION_ENDORSED, POC_1, VA_POC, About.SUBMISSION, "state_to", "ENDORSED"),
            row(Kind.EXEMPTION_DECISION, AuditAction.EXEMPTION_REQUESTED, SMO_B, SMO, About.SUBMISSION,
                    "state_from", "NOT_STARTED", "basis", "SCHEDULED_VA"),
            row(Kind.EXEMPTION_DECISION, AuditAction.EXEMPTION_APPROVED, POC_1, VA_POC, About.SUBMISSION, "state_to", "EXEMPTED"),
            row(Kind.EXEMPTION_DECISION, AuditAction.EXEMPTION_REJECTED, POC_1, VA_POC, About.SUBMISSION,
                    "state_to", "NOT_STARTED"),
            row(Kind.REGISTER_CHANGE, AuditAction.REGISTER_APPLIED, POC_1, VA_POC, About.NOTHING),
            row(Kind.REGISTER_CHANGE, AuditAction.REGISTER_SYSTEM_RETIRED, POC_1, VA_POC, About.SYSTEM),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_GRANTED, POC_1, VA_POC, About.SYSTEM, "role", "SMO"),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_REVOKED, POC_1, VA_POC, About.SYSTEM, "role", "CYBER_MANAGER"),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_SUSPENDED, POC_1, VA_POC, About.SYSTEM, "reason", "review"),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_RESTORED, POC_1, VA_POC, About.SYSTEM),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_HOLDERS_REVIEWED, POC_1, VA_POC, About.NOTHING),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_LAPSED, null, null, About.SYSTEM, "role", "CYBER_MANAGER"),
            row(Kind.ROLE_CHANGE, AuditAction.ROLE_RETURNED, POC_1, VA_POC, About.SYSTEM, "reason", "register"),
            row(Kind.EXPORT, AuditAction.EXPORT_REQUESTED, POC_1, VA_POC, About.NOTHING, "export_type", "MGMT_SUMMARY"),
            row(Kind.DISPOSAL, AuditAction.DISPOSAL_LISTED, null, null, About.NOTHING),
            row(Kind.DISPOSAL, AuditAction.DISPOSAL_APPROVED, POC_1, VA_POC, About.NOTHING),
            row(Kind.DISPOSAL, AuditAction.DISPOSAL_EXECUTED, null, null, About.NOTHING),
            row(Kind.ACCESS, AuditAction.SIGN_IN, SMO_A, SMO, About.NOTHING),
            row(Kind.ACCESS, AuditAction.SIGN_OUT, SMO_A, SMO, About.NOTHING),
            row(Kind.ACCESS, AuditAction.STEP_UP_STARTED, POC_1, VA_POC, About.NOTHING, "intent", "submission-return"),
            row(Kind.ACCESS, AuditAction.STEP_UP_OK, POC_1, VA_POC, About.NOTHING, "intent", "submission-return"),
            refusal(Kind.ACCESS, AuditAction.REQUEST_REFUSED, SMO_A, SMO, About.NOTHING, "reason", "forbidden", "status", "403"),
            refusal(Kind.ACCESS, AuditAction.REQUEST_REFUSED, POC_1, VA_POC, About.NOTHING, "reason", "step-up-required",
                    "intent", "submission-return"));

    /**
     * The audited actions this walk does not make, each with the Seam 1 test that asserts its entry (its source must name
     * the action): what needs a failing identity provider, a rescan, a deletion or a refused mail address is set up better
     * there.
     */
    static final Map<AuditAction, String> COVERED_ELSEWHERE = Map.ofEntries(
            Map.entry(AuditAction.SIGN_IN_FAILED, "audit/AuditTrailTest"),
            Map.entry(AuditAction.STEP_UP_FULL_SIGN_IN, "access/StepUpFullSignInTest"),
            Map.entry(AuditAction.STEP_UP_FAILED, "audit/AuditTrailTest"),
            Map.entry(AuditAction.FILE_REPLACED, "ingestion/ReplaceFileRulesTest"),
            Map.entry(AuditAction.PROPOSAL_WITHDRAWN, "ingestion/ReplaceFileTest"),
            Map.entry(AuditAction.FILE_DELETED, "ingestion/DeleteReportFileTest"),
            Map.entry(AuditAction.FINDING_DELETED, "ingestion/DeleteManualFindingTest"),
            Map.entry(AuditAction.SMO_UNREACHABLE, "ingestion/SmoUnreachableTest"),
            Map.entry(AuditAction.SMO_UNREACHABLE_CLEARED, "ingestion/SmoUnreachableTest"),
            Map.entry(AuditAction.EXEMPTION_LAPSED, "ingestion/StateTableTest"),
            Map.entry(AuditAction.DECLARATION_PUBLISHED, "ingestion/DeclarationPublishTest"));

    /** Where the Seam 1 tests' sources are, from the back end's directory, where the build runs them. */
    static final Path TESTS = Path.of("src/test/java/sg/annualva");

    /** The actions about no one System: their entries are told from the rest of the suite's by what was there before. */
    static final Set<AuditAction> ABOUT_NO_SYSTEM = ROWS.stream().filter(row -> row.about() == About.NOTHING)
            .map(Row::action).collect(Collectors.toSet());

    @Test
    void everyAuditedActionIsWalkedHereOrNamedWithTheTestThatAssertsIt() throws IOException {
        Set<AuditAction> walked = ROWS.stream().map(Row::action).collect(Collectors.toSet());
        for (AuditAction action : AuditAction.values()) {
            assertThat(walked.contains(action) || COVERED_ELSEWHERE.containsKey(action))
                    .as(action + " has a row, or names the test that covers it").isTrue();
        }
        for (var covered : COVERED_ELSEWHERE.entrySet()) {
            assertThat(Files.readString(TESTS.resolve(covered.getValue() + ".java")))
                    .as(covered.getValue() + " asserts the entry of " + covered.getKey()).contains(covered.getKey().name());
        }
        assertThat(Arrays.stream(Kind.values()).filter(kind -> ROWS.stream().noneMatch(row -> row.kind() == kind)))
                .as("every kind of action story 84 names is walked").isEmpty();
    }

    @Test
    void everyAuditedActionHasItsWordsOnTheAuditLogScreen() throws IOException {
        Path labels = Path.of("../frontend/src/api/audit.ts");
        Assumptions.assumeTrue(Files.exists(labels), "the front end is checked out beside the back end");
        String screen = Files.readString(labels);
        for (AuditAction action : AuditAction.values()) {
            assertThat(screen).as(action + " is labelled in the audit log viewer's action filter")
                    .containsPattern("\\b" + action.name() + "\\s*:");
        }
    }

    private final Map<String, String> people = new HashMap<>();
    private final Map<String, String> systems = new LinkedHashMap<>();

    @TestFactory
    Stream<DynamicTest> everyAuditedActionWritesItsEntryWithActorRoleOutcomeAndIdsAndNoText() {
        Map<AuditAction, Set<String>> before = new EnumMap<>(AuditAction.class);
        ApiClient reader = otherVaPoc();
        ABOUT_NO_SYSTEM.forEach(action -> before.put(action, ids(reader, "action=" + action)));

        walk();

        ApiClient after = otherVaPoc();
        List<JsonNode> written = new ArrayList<>();
        systems.values().forEach(system -> written.addAll(AuditEntries.all(after, "system=" + system)));
        ABOUT_NO_SYSTEM.forEach(action -> AuditEntries.all(after, "action=" + action).stream()
                .filter(entry -> !before.get(action).contains(entry.get("id").asString())).forEach(written::add));

        return Stream.concat(
                Stream.of(DynamicTest.dynamicTest("no entry holds free text", () -> assertThat(written)
                        .allSatisfy(entry -> assertThat(entry.toString()).doesNotContain(SENTINEL)))),
                ROWS.stream().map(row -> DynamicTest.dynamicTest(row.toString(), () -> assertWritten(row, written))));
    }

    private void assertWritten(Row row, List<JsonNode> written) {
        List<JsonNode> ofAction = written.stream()
                .filter(entry -> entry.get("action").asString().equals(row.action().name())).toList();
        assertThat(ofAction).as("entries of " + row.action()).isNotEmpty();
        assertThat(ofAction).as("one entry as the row says, among " + ofAction).anySatisfy(entry -> {
            assertThat(entry.get("outcome").asString()).isEqualTo(row.outcome());
            if (row.actor() == null) {
                assertThat(entry.get("actorId").isNull()).as("a job acts for nobody").isTrue();
            } else {
                assertThat(entry.get("actorId").asString()).isEqualTo(people.get(row.actor()));
                assertThat(entry.get("actorRole").asString().split(",")).contains(row.role());
            }
            switch (row.about()) {
                case FINDING -> assertThat(entry.get("findingId").isNull() || entry.get("systemId").isNull()).isFalse();
                case SUBMISSION -> assertThat(entry.get("submissionId").isNull() || entry.get("systemId").isNull())
                        .isFalse();
                case SYSTEM -> assertThat(entry.get("systemId").isNull()).isFalse();
                case NOTHING -> { }
            }
            row.detail().forEach((key, value) -> assertThat(entry.get("detail").path(key).asString()).as(key)
                    .isEqualTo(value));
            assertThat(entry.get("correlationId").asString()).isNotBlank();
        });
    }

    // ---- the walk ----

    /**
     * One VA Cycle's life, by every role: Q3 of VA Cycle 2027 from 2 August 2027, then the sweep, the export and its
     * disposal, the role lapse in November and the Cycle's close in February 2028.
     */
    private void walk() {
        people.put(SMO_A, userId(signedInAs(SMO_A)));
        people.put(SMO_B, userId(signedInAs(SMO_B)));
        people.put(CM, userId(signedInAs(CM)));
        people.put(POC_2, userId(otherVaPoc()));
        ApiClient poc = vaPoc();
        people.put(POC_1, userId(poc));
        assertThat(poc.download("/api/system-register/template").status()).isEqualTo(200);
        var rejected = upload(poc, "not a workbook".getBytes(StandardCharsets.UTF_8));
        assertThat(rejected.status()).as(rejected.body()).isBetween(400, 499);
        RegisterXlsx everything = file(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"), listed("SYS-F3", "File three", SMO_A, CM, "Q3"),
                listed("SYS-F4", "File four", SMO_B, CM, "Q3"), listed("SYS-F5", "File five", SMO_A, CM, "Q3")));
        JsonNode preview = preview(poc, everything);
        assertThat(poc.get("/api/system-register/previews/" + preview.get("id").asString()).status()).isEqualTo(200);
        apply(poc, preview);
        for (String code : List.of("SYS-F1", "SYS-F2", "SYS-F3", "SYS-F4", "SYS-F5")) {
            systems.put(code, idOf(code));
        }

        Prepared prepared = preparedAndSubmitted();
        reviewedAndReturned(prepared);
        String sub = prepared.submissionId();
        reworkedAndResubmitted(sub);
        passedReturnedByAVaPocAndEndorsed(sub);
        String exemption = exemptionsDecided();
        registerChangedAndRolesReviewed();

        today("2027-08-17");
        arrange.overdueSweep();
        exportedAndDisposed();

        // More than 90 days on: SYS-F2's SMO/Tech Lead records the VA and both VA POCs act, so only the others' roles lapse.
        today("2027-11-16");
        recordedVa(signedInAs(SMO_B), exemption, vaHappened("2027-11-15", "Done by Acme. " + SENTINEL));
        confirmedVa(vaPoc(), exemption);
        otherVaPoc();
        arrange.roleLapse();
        ApiClient again = vaPoc();
        apply(again, preview(again, file(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"), listed("SYS-F5", "File five", SMO_A, CM, "Q3")))));

        today("2028-02-14");
        arrange.cycleLifecycle();
        CycleLifecycleSeam1.closed(vaPoc(), 2027);
    }

    /** SYS-F1's VA Submission, its parsed Finding (proposed alone) and the Bulk-set batch of its Finding entered by hand. */
    private record Prepared(String submissionId, String parsed, String batch) {}

    /** SYS-F1 started, a report uploaded and one rejected, its Scan Reports set, every Finding proposed, submitted. */
    private Prepared preparedAndSubmitted() {
        ApiClient smo = signedInAs(SMO_A);
        String sub = started(smo, systems.get("SYS-F1")).get("id").asString();
        Draft draft = new Draft(smo, systems.get("SYS-F1"), sub);
        String file = parsedDc(draft, "dc.json", dcReportAt(SCANNED));
        metadata(smo, sub, file);
        confirmed(smo, sub, file, "2027-07-20", "app.example");
        scanner.nextFates(FakeMalwareScanner.Fate.BLOCKED);
        settledEvidence(draft, DEPENDENCY_CHECK, "report.pdf", Samples.pdf());
        modeSet(smo, sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only. " + SENTINEL));
        String manual = entered(smo, sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH")).get("id").asString();
        modeSet(smo, sub, BURP_SUITE, mode(NOT_APPLICABLE, "No web interface. " + SENTINEL));
        String parsed = findingIds(smo, sub).stream().filter(id -> !id.equals(manual)).findFirst().orElseThrow();
        proposed(smo, parsed, falsePositive("Not reachable. " + SENTINEL));
        String batch = BatchReviewSeam1.bulkSet(draft, List.of(manual), falsePositive("Compensated by SSO. " + SENTINEL));
        assertThat(smo.get(BatchReviewSeam1.batchesOf(sub)).status()).isEqualTo(200);
        reports(smo, sub);
        submitted(smo, sub);
        return new Prepared(sub, parsed, batch);
    }

    /** The Cyber Manager reads it, its files and its progress, Disputes one Finding, confirms the rest and returns it. */
    private void reviewedAndReturned(Prepared prepared) {
        String sub = prepared.submissionId();
        String parsed = prepared.parsed();
        ApiClient cm = signedInAs(CM);
        assertThat(read(cm, sub).status()).isEqualTo(200);
        JsonNode files = ApiClient.json(cm.get(filesOf(sub)));
        String parsedFile = null;
        for (JsonNode file : files) {
            if (file.get("status").asString().equals("PARSED")) {
                parsedFile = file.get("id").asString();
            }
        }
        assertThat(cm.download(fileAt(sub, parsedFile) + "/content").status()).isEqualTo(200);
        assertThat(cm.get(readinessOf(sub)).status()).isEqualTo(200);
        String batch = prepared.batch();
        assertThat(cm.get(proposalsOf(parsed)).status()).isEqualTo(200);
        reviewed(cm, sub, parsed, DISPUTED, "Show the upgrade. " + SENTINEL);
        BatchReviewSeam1.batchReviewed(cm, batch, CONFIRMED, null);
        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        returned(cm, sub, "One item needs another look. " + SENTINEL);
        assertThat(cm.get("/api/audit-entries?submission=" + sub).status())
                .as("the history of what they reviewed").isEqualTo(200);
    }

    private void reworkedAndResubmitted(String sub) {
        ApiClient smo = signedInAs(SMO_A);
        assertThat(smo.get(SUBMISSIONS + "/" + sub + "/rework-queue").status()).isEqualTo(200);
        returns(smo, sub);
        submitted(smo, sub);
    }

    /**
     * The Cyber Manager confirms what changed and passes it on; the first VA POC, whose sign-in has gone stale, steps up,
     * returns it, and endorses it once it is back.
     */
    private void passedReturnedByAVaPocAndEndorsed(String sub) {
        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);
        passed(cm, sub);
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(6));
        assertThat(returnForRework(poc, sub, "Plan dates. " + SENTINEL).status()).isEqualTo(403);
        SignIn.stepUp(poc, issuer, "/auth/step-up?intent=submission-return&returnTo=/va-poc",
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        poc.withCsrf();
        returned(poc, sub, "Plan dates. " + SENTINEL);
        submitted(signedInAs(SMO_A), sub);
        ApiClient again = signedInAs(CM);
        confirmEverything(again, sub);
        passed(again, sub);
        endorsed(vaPoc(), sub);
    }

    /** SYS-F2's scheduled-VA Exemption approved, SYS-F3's prior-VA one rejected; the approved one's id. */
    private String exemptionsDecided() {
        ApiClient smoB = signedInAs(SMO_B);
        String approved = requested(smoB, systems.get("SYS-F2"), exemption(SCHEDULED_VA, "2027-11-15",
                "Booked with Acme. " + SENTINEL, null)).get("id").asString();
        ApiClient other = otherVaPoc();
        queue(other);
        approved(vaPoc(), approved);
        String refused = requested(signedInAs(SMO_A), systems.get("SYS-F3"), exemption(PRIOR_VA, "2027-07-15",
                "Report AC-1. " + SENTINEL, null)).get("id").asString();
        rejected(vaPoc(), refused);
        return approved;
    }

    /**
     * SYS-F3 (Not Started after the rejection) and SYS-F4 (a Draft, withdrawn) retired; the review suspends SYS-F2's
     * SMO/Tech Lead and restores them, and is recorded; an SMO/Tech Lead is refused a VA POC's screen and signs out.
     */
    private void registerChangedAndRolesReviewed() {
        ApiClient smoB = signedInAs(SMO_B);
        String draft = started(smoB, systems.get("SYS-F4")).get("id").asString();
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"), listed("SYS-F5", "File five", SMO_A, CM, "Q3"))));
        assertThat(applyConfirming(poc, preview, List.of(draft)).status()).isEqualTo(200);

        JsonNode holders = ApiClient.json(poc.get("/api/role-holders"));
        String grant = null;
        for (JsonNode holder : holders.get("holders")) {
            if (holder.get("role").asString().equals("SMO") && holder.get("email").asString().equalsIgnoreCase(SMO_B)
                    && "SYS-F2".equals(holder.get("systemCode").asString(""))) {
                grant = holder.get("id").asString();
            }
        }
        assertThat(poc.post("/api/role-holders/" + grant + "/suspend").status()).isEqualTo(200);
        assertThat(poc.post("/api/role-holders/" + grant + "/restore").status()).isEqualTo(200);
        assertThat(poc.post("/api/role-holders/reviews").status()).isEqualTo(201);
        assertThat(poc.get("/api/audit-entries?action=SUBMISSION_ENDORSED&system=" + systems.get("SYS-F1")).status())
                .isEqualTo(200);

        ApiClient smo = signedInAs(SMO_A);
        assertThat(smo.get("/api/dashboards/cycle-matrix").status()).isEqualTo(403);
        assertThat(smo.post("/api/logout").status()).isEqualTo(204);
    }

    /** The management summary asked for and downloaded; a day later it has expired, is listed, approved and disposed of. */
    private void exportedAndDisposed() {
        ApiClient poc = vaPoc();
        var asked = poc.postJson("/api/exports", "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\"}");
        assertThat(asked.status()).as(asked.body()).isEqualTo(202);
        arrange.exports();
        String export = ApiClient.json(asked).get("id").asString();
        assertThat(poc.download("/api/exports/" + export + "/download").status()).isEqualTo(200);
        clock.advance(Duration.ofHours(25));
        arrange.exports();
        arrange.retention();
        ApiClient approver = vaPoc();
        String list = DisposalSeam1.listIn(approver, "DRAFT").get("id").asString();
        DisposalSeam1.approved(approver, list);
        arrange.retention();
    }

    // ---- reading the log ----

    private static Set<String> ids(ApiClient reader, String query) {
        return AuditEntries.all(reader, query).stream().map(entry -> entry.get("id").asString())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
