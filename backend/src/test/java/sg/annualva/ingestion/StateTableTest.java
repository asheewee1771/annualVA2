package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import sg.annualva.submission.SubmissionState;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 50: the spec's VA Submission state table, row by row, through the HTTP API. Every state is reached by the real
 * commands (SYS-F1, SMO_A and CM, Q3 of VA Cycle 2027), then every command that the table does not allow from it is
 * sent and must answer 409 {@code invalid-transition}, change nothing and be audited as refused; and every move the
 * table allows is made and lands where the table says. The Overdue flag is read after the sweep at the end of week 2,
 * week 4 and the Quarter. {@code NONE} is a System with no VA Submission, which the screens show as Not Started.
 */
class StateTableTest extends ExemptionSeam1 {

    static final String NONE = "NONE";
    static final String NOT_STARTED = "NOT_STARTED";
    static final String DRAFT = "DRAFT";
    static final String IN_REVIEW = "IN_REVIEW";
    static final String WITHDRAWN = "WITHDRAWN";
    static final String NOT_ENDORSED = "NOT_ENDORSED";
    static final String INVALID_TRANSITION = "invalid-transition";

    /** Every state of the table, in its order; {@code NONE} first. */
    static final List<String> STATES = List.of(NONE, NOT_STARTED, DRAFT, IN_REVIEW, RETURNED_FOR_REWORK,
            AWAITING_ENDORSEMENT, ENDORSED, EXEMPTION_REQUESTED, EXEMPTED, WITHDRAWN, NOT_ENDORSED);

    /** Who sends a command. */
    enum Actor { SMO, CYBER_MANAGER, VA_POC }

    /** What a command needs of the VA Submission to be sent at all. */
    enum Needs { SYSTEM, SUBMISSION, FINDING, FILE, BATCH, EXEMPTION }

    /** Every command a VA Submission answers, with the states the table allows it in and the entry it is audited as. */
    enum Command {
        START(Actor.SMO, Needs.SYSTEM, "SUBMISSION_STARTED", NONE, NOT_STARTED),
        SUBMIT(Actor.SMO, Needs.SUBMISSION, "SUBMISSION_SUBMITTED", DRAFT, RETURNED_FOR_REWORK),
        REQUEST_EXEMPTION(Actor.SMO, Needs.SYSTEM, "EXEMPTION_REQUESTED", NONE, NOT_STARTED, DRAFT, IN_REVIEW,
                RETURNED_FOR_REWORK, AWAITING_ENDORSEMENT),
        REVIEW_FINDING(Actor.CYBER_MANAGER, Needs.FINDING, "FINDING_REVIEWED", IN_REVIEW),
        REVIEW_JUSTIFICATION(Actor.CYBER_MANAGER, Needs.SUBMISSION, "REPORT_REVIEWED", IN_REVIEW),
        REVIEW_BATCH(Actor.CYBER_MANAGER, Needs.BATCH, "BATCH_REVIEWED", IN_REVIEW),
        RETURN_FROM_REVIEW(Actor.CYBER_MANAGER, Needs.SUBMISSION, "SUBMISSION_RETURNED", IN_REVIEW),
        PASS_TO_ENDORSEMENT(Actor.CYBER_MANAGER, Needs.SUBMISSION, "SUBMISSION_PASSED", IN_REVIEW),
        RETURN_FROM_ENDORSEMENT(Actor.VA_POC, Needs.SUBMISSION, "SUBMISSION_RETURNED", AWAITING_ENDORSEMENT),
        ENDORSE(Actor.VA_POC, Needs.SUBMISSION, "SUBMISSION_ENDORSED", AWAITING_ENDORSEMENT),
        APPROVE_EXEMPTION(Actor.VA_POC, Needs.EXEMPTION, "EXEMPTION_APPROVED", EXEMPTION_REQUESTED),
        REJECT_EXEMPTION(Actor.VA_POC, Needs.EXEMPTION, "EXEMPTION_REJECTED", EXEMPTION_REQUESTED),
        RECORD_VA(Actor.SMO, Needs.EXEMPTION, "EXEMPTION_VA_RECORDED", EXEMPTED),
        CONFIRM_VA(Actor.VA_POC, Needs.EXEMPTION, "EXEMPTION_VA_CONFIRMED", EXEMPTED),
        SET_MODE(Actor.SMO, Needs.SUBMISSION, "REPORT_MODE_SET", DRAFT, RETURNED_FOR_REWORK),
        ENTER_FINDING(Actor.SMO, Needs.SUBMISSION, "FINDING_ENTERED", DRAFT, RETURNED_FOR_REWORK),
        PROPOSE(Actor.SMO, Needs.FINDING, "STATUS_PROPOSED", DRAFT, RETURNED_FOR_REWORK),
        BULK_PROPOSE(Actor.SMO, Needs.FINDING, "BATCH_PROPOSED", DRAFT, RETURNED_FOR_REWORK),
        UPLOAD(Actor.SMO, Needs.SUBMISSION, "FILE_UPLOADED", DRAFT, RETURNED_FOR_REWORK),
        REPLACE(Actor.SMO, Needs.FILE, "FILE_UPLOADED", DRAFT, RETURNED_FOR_REWORK),
        CONFIRM_SCAN_DATE(Actor.SMO, Needs.FILE, "FILE_METADATA_CONFIRMED", DRAFT, RETURNED_FOR_REWORK);

        final Actor actor;
        final Needs needs;
        final String audited;
        final Set<String> allowedIn;

        Command(Actor actor, Needs needs, String audited, String... allowedIn) {
            this.actor = actor;
            this.needs = needs;
            this.audited = audited;
            this.allowedIn = Set.of(allowedIn);
        }
    }

    /**
     * SYS-F1 in one state, with what the commands address: its VA Submission, a Finding and its current proposal, the
     * Dependency-Check file, the Bulk-set batch, the version of the Burp Suite justification and the Exemption; null where
     * it has none.
     */
    record At(String systemId, @Nullable String submissionId, @Nullable String findingId,
            @Nullable String proposalId, @Nullable String fileId, @Nullable String batchId, int justificationVersion,
            @Nullable String exemptionId) {

        /** The same System once a request or a start has made its VA Submission, and the Exemption if one was asked for. */
        At with(String submission, @Nullable String exemption) {
            return new At(systemId, submission, findingId, proposalId, fileId, batchId, justificationVersion, exemption);
        }

        boolean has(Needs needs) {
            return switch (needs) {
                case SYSTEM -> true;
                case SUBMISSION -> submissionId != null;
                case FINDING -> findingId != null;
                case FILE -> fileId != null;
                case BATCH -> batchId != null;
                case EXEMPTION -> exemptionId != null;
            };
        }
    }

    /** A prior VA inside the 3 months before Q3 of VA Cycle 2027, for that Cycle whatever the date. */
    static final String PRIOR_VA_2027 =
            "{\"basis\":\"PRIOR_VA\",\"vaDate\":\"2027-07-15\",\"evidence\":\"Third-party VA, ref AC-1.\",\"cycle\":2027}";

    /** The Overdue sweep's checkpoints: just after the Submission Window, the Review Window and the Quarter. */
    static final List<String> CHECKPOINTS = List.of("2027-08-17", "2027-08-31", "2027-11-02");

    /** The states flagged Overdue at each checkpoint (spec: "Overdue is a flag, not a state"). */
    static final Map<String, Set<String>> OVERDUE_AT = Map.of(
            "2027-08-17", Set.of(NONE, NOT_STARTED, DRAFT),
            "2027-08-31", Set.of(NONE, NOT_STARTED, DRAFT, IN_REVIEW, RETURNED_FOR_REWORK),
            "2027-11-02", Set.of(NONE, NOT_STARTED, DRAFT, IN_REVIEW, RETURNED_FOR_REWORK, AWAITING_ENDORSEMENT));

    @Test
    void theTableNamesEveryStateTheApplicationHas() {
        assertThat(STATES).containsAll(Arrays.stream(SubmissionState.values()).map(Enum::name).toList());
    }

    static Stream<String> states() {
        return STATES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("states")
    void eachStateRefusesEveryCommandTheTableDoesNotAllowAndIsFlaggedOverdueOnlyWhereItSays(String state) {
        At at = arrive(state);
        Map<String, Integer> refusedBefore = refusedByAction(at.systemId());
        Map<String, Integer> probed = new HashMap<>();

        Map<Actor, ApiClient> actors = actors();
        for (Command command : Command.values()) {
            if (command.allowedIn.contains(state) || !at.has(command.needs)) {
                continue;
            }
            var answer = send(command, at, actors.get(command.actor));
            if (state.equals(WITHDRAWN) && command.actor != Actor.VA_POC) {
                // Retiring the System revoked its SMO/Tech Lead's and Cyber Manager's roles for it (ADR 0049).
                assertThat(answer.status()).as(command + " in " + state + ": " + answer.body()).isEqualTo(404);
                continue;
            }
            assertThat(answer.status()).as(command + " in " + state + ": " + answer.body()).isEqualTo(409);
            assertThat(ApiClient.json(answer).get("type").asString()).as(command + " in " + state)
                    .isEqualTo("https://annualva/problems/" + INVALID_TRANSITION);
            probed.merge(command.audited, 1, Integer::sum);
        }

        assertThat(stateOf(at)).as("no refused command moved it").isEqualTo(state.equals(NONE) ? NOT_STARTED : state);
        Map<String, Integer> refusedAfter = refusedByAction(at.systemId());
        probed.forEach((action, count) -> assertThat(refusedAfter.getOrDefault(action, 0)
                - refusedBefore.getOrDefault(action, 0)).as("refused " + action + " entries").isEqualTo(count));

        for (String checkpoint : state.equals(NOT_ENDORSED) ? List.of("2028-02-14") : CHECKPOINTS) {
            today(checkpoint);
            arrange.overdueSweep();
            assertThat(overdue(at)).as(state + " Overdue on " + checkpoint)
                    .isEqualTo(OVERDUE_AT.getOrDefault(checkpoint, Set.of()).contains(state));
        }
    }

    /** Every move of the table: from a state, by a command (or the job or the Register apply), to a state. */
    static Stream<Arguments> moves() {
        return Stream.of(
                Arguments.of(NONE, "START", DRAFT),
                Arguments.of(NOT_STARTED, "START", DRAFT),
                Arguments.of(DRAFT, "SUBMIT", IN_REVIEW),
                Arguments.of(RETURNED_FOR_REWORK, "SUBMIT", IN_REVIEW),
                Arguments.of(IN_REVIEW, "RETURN_FROM_REVIEW", RETURNED_FOR_REWORK),
                Arguments.of(IN_REVIEW, "PASS_TO_ENDORSEMENT", AWAITING_ENDORSEMENT),
                Arguments.of(AWAITING_ENDORSEMENT, "ENDORSE", ENDORSED),
                Arguments.of(AWAITING_ENDORSEMENT, "RETURN_FROM_ENDORSEMENT", RETURNED_FOR_REWORK),
                Arguments.of(NONE, "REQUEST_EXEMPTION", EXEMPTION_REQUESTED),
                Arguments.of(NOT_STARTED, "REQUEST_EXEMPTION", EXEMPTION_REQUESTED),
                Arguments.of(DRAFT, "REQUEST_EXEMPTION", EXEMPTION_REQUESTED),
                Arguments.of(IN_REVIEW, "REQUEST_EXEMPTION", EXEMPTION_REQUESTED),
                Arguments.of(RETURNED_FOR_REWORK, "REQUEST_EXEMPTION", EXEMPTION_REQUESTED),
                Arguments.of(AWAITING_ENDORSEMENT, "REQUEST_EXEMPTION", EXEMPTION_REQUESTED),
                Arguments.of(EXEMPTION_REQUESTED, "APPROVE_EXEMPTION", EXEMPTED),
                Arguments.of(EXEMPTION_REQUESTED, "REJECT_EXEMPTION", DRAFT),
                Arguments.of(IN_REVIEW, "REQUEST_THEN_REJECT", IN_REVIEW),
                Arguments.of(RETURNED_FOR_REWORK, "REQUEST_THEN_REJECT", RETURNED_FOR_REWORK),
                Arguments.of(AWAITING_ENDORSEMENT, "REQUEST_THEN_REJECT", AWAITING_ENDORSEMENT),
                Arguments.of(NONE, "REQUEST_THEN_REJECT", NOT_STARTED),
                Arguments.of(EXEMPTED, "LAPSE", DRAFT),
                Arguments.of(NONE, "REQUEST_APPROVE_LAPSE", NOT_STARTED),
                Arguments.of(DRAFT, "WITHDRAW", WITHDRAWN),
                Arguments.of(IN_REVIEW, "WITHDRAW", WITHDRAWN),
                Arguments.of(RETURNED_FOR_REWORK, "WITHDRAW", WITHDRAWN),
                Arguments.of(AWAITING_ENDORSEMENT, "WITHDRAW", WITHDRAWN),
                Arguments.of(EXEMPTION_REQUESTED, "WITHDRAW", WITHDRAWN),
                Arguments.of(NONE, "CLOSE_CYCLE", NOT_ENDORSED),
                Arguments.of(NOT_STARTED, "CLOSE_CYCLE", NOT_ENDORSED),
                Arguments.of(DRAFT, "CLOSE_CYCLE", NOT_ENDORSED),
                Arguments.of(IN_REVIEW, "CLOSE_CYCLE", NOT_ENDORSED),
                Arguments.of(RETURNED_FOR_REWORK, "CLOSE_CYCLE", NOT_ENDORSED),
                Arguments.of(AWAITING_ENDORSEMENT, "CLOSE_CYCLE", NOT_ENDORSED),
                Arguments.of(EXEMPTION_REQUESTED, "CLOSE_CYCLE", NOT_ENDORSED));
    }

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("moves")
    void eachMoveTheTableAllowsLandsWhereItSaysAndIsAudited(String from, String move, String to) {
        At at = arrive(from);

        At after = make(move, at);

        assertThat(stateOf(after)).isEqualTo(to);
        JsonNode entry = newestSuccess(audited(move), at.systemId());
        assertThat(entry.get("detail").get("state_to").asString()).isEqualTo(to);
        if (entry.get("detail").has("state_from")) {
            assertThat(entry.get("detail").get("state_from").asString()).isEqualTo(switch (move) {
                case "REQUEST_THEN_REJECT" -> EXEMPTION_REQUESTED;
                case "REQUEST_APPROVE_LAPSE" -> EXEMPTED;
                default -> from.equals(NONE) ? NOT_STARTED : from;
            });
        }
    }

    @Test
    void everyStateThatIsNotFinalHasItsMovesInTheTable() {
        Set<String> movedFrom = moves().map(arguments -> (String) arguments.get()[0]).collect(Collectors.toSet());
        for (SubmissionState state : SubmissionState.values()) {
            if (!state.isFinal()) {
                assertThat(movedFrom).as(state + " is not final, so the table moves it on").contains(state.name());
            }
        }
    }

    // ---- arriving at a state through the real commands ----

    /** SYS-F1 in this state, reached through the real commands. */
    At arrive(String state) {
        return switch (state) {
            case NONE -> {
                registered(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                        listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3")), List.of());
                yield new At(idOf("SYS-F1"), null, null, null, null, null, 0, null);
            }
            case NOT_STARTED -> {
                yield make("REQUEST_THEN_REJECT", arrive(NONE));
            }
            case DRAFT -> prepared(readyDraft());
            case IN_REVIEW -> prepared(inReview());
            case RETURNED_FOR_REWORK -> prepared(returnedForRework());
            case AWAITING_ENDORSEMENT -> prepared(awaitingEndorsement());
            case ENDORSED -> make("ENDORSE", arrive(AWAITING_ENDORSEMENT));
            case EXEMPTION_REQUESTED -> make("REQUEST_SCHEDULED_EXEMPTION", arrive(DRAFT));
            case EXEMPTED -> make("APPROVE_EXEMPTION", arrive(EXEMPTION_REQUESTED));
            case WITHDRAWN -> make("WITHDRAW", arrive(DRAFT));
            case NOT_ENDORSED -> make("CLOSE_CYCLE", arrive(DRAFT));
            default -> throw new IllegalArgumentException(state);
        };
    }

    /** What a started VA Submission holds that the commands address, read by its SMO/Tech Lead. */
    private At prepared(Draft draft) {
        String sub = draft.submissionId();
        ApiClient smo = signedInAs(SMO_A);
        JsonNode finding = findings(smo, sub).get("items").get(0);
        JsonNode files = ApiClient.json(smo.get(filesOf(sub)));
        String fileId = files.get(0).get("id").asString();
        return new At(draft.systemId(), sub, finding.get("id").asString(),
                finding.get("currentProposal").get("id").asString(), fileId,
                BatchReviewSeam1.batches(smo, sub).get(0).get("batchId").asString(),
                justificationVersion(smo, sub, BURP_SUITE), null);
    }

    /** The ready Draft with its Finding entered by hand in a Bulk-set batch, so a batch review is a command to send too. */
    @Override
    protected Draft readyDraft() {
        Draft draft = super.readyDraft();
        List<String> findings = findingIds(draft.smo(), draft.submissionId());
        BatchReviewSeam1.bulkSet(draft, List.of(findings.getLast()), falsePositive("Compensated by SSO."));
        return draft;
    }

    // ---- sending the commands ----

    /** Each actor signed in now: SYS-F1's SMO/Tech Lead and Cyber Manager, and the first VA POC, fresh for a step-up. */
    private Map<Actor, ApiClient> actors() {
        return Map.of(Actor.SMO, signedInAs(SMO_A), Actor.CYBER_MANAGER, signedInAs(CM), Actor.VA_POC, vaPoc());
    }

    private static ApiClient.Response send(Command command, At at, ApiClient client) {
        String sub = at.submissionId();
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        return switch (command) {
            case START -> start(client, at.systemId(), 2027);
            case SUBMIT -> submitAccepting(client, sub);
            case REQUEST_EXEMPTION -> request(client, at.systemId(), PRIOR_VA_2027);
            case REVIEW_FINDING -> reviewFinding(client, at.findingId(), review(CONFIRMED, null, at.proposalId()));
            case REVIEW_JUSTIFICATION -> reviewReport(client, sub, BURP_SUITE,
                    justificationReview(CONFIRMED, null, at.justificationVersion()));
            case RETURN_FROM_REVIEW, RETURN_FROM_ENDORSEMENT -> returnForRework(client, sub, "Another look, please.");
            case REVIEW_BATCH -> BatchReviewSeam1.reviewBatch(client, at.batchId(), "{\"decision\":\"CONFIRMED\"}");
            case PASS_TO_ENDORSEMENT -> passToEndorsement(client, sub);
            case ENDORSE -> endorse(client, sub);
            case APPROVE_EXEMPTION -> approve(client, at.exemptionId());
            case REJECT_EXEMPTION -> reject(client, at.exemptionId());
            case RECORD_VA -> recordVa(client, at.exemptionId(), vaHappened("2027-11-15", "Done by Acme."));
            case CONFIRM_VA -> confirmVa(client, at.exemptionId());
            case SET_MODE -> setMode(client, sub, DEPENDENCY_CHECK, mode(NOT_APPLICABLE, "No libraries."));
            case ENTER_FINDING -> enter(client, sub, manualFinding(SECURE_CONFIGURATION, "Audit log off", "SC-LOG-1",
                    "audit-log", "LOW"));
            case PROPOSE -> propose(client, at.findingId(), falsePositive("Not reachable."));
            case BULK_PROPOSE -> client.postJson(SUBMISSIONS + "/" + sub + "/findings/bulk-proposals",
                    "{\"findingIds\":[" + quoted(at.findingId()) + "]," + falsePositive("Not reachable.").substring(1));
            case UPLOAD -> upload(client, sub, DEPENDENCY_CHECK, MACHINE_READABLE, "dc.json", bytes);
            case REPLACE -> ReplaceSeam1.replace(client, sub, at.fileId(), "dc.json", bytes);
            case CONFIRM_SCAN_DATE -> confirm(client, sub, at.fileId(), confirmation("2027-07-20", "app.example"));
        };
    }

    /**
     * Makes one move of the table with what it needs to succeed; the System as it is after it (a start from a System
     * with none names the new VA Submission).
     */
    private At make(String move, At at) {
        String sub = at.submissionId();
        switch (move) {
            case "START" -> {
                var response = start(signedInAs(SMO_A), at.systemId());
                assertThat(response.status()).as(response.body()).isEqualTo(201);
                return at.with(ApiClient.json(response).get("id").asString(), at.exemptionId());
            }
            case "SUBMIT" -> submitted(signedInAs(SMO_A), sub);
            case "RETURN_FROM_REVIEW" -> {
                ApiClient cm = signedInAs(CM);
                reviewed(cm, sub, at.findingId(), DISPUTED, "Show the upgrade.");
                returned(cm, sub, "One item needs another look.");
            }
            case "PASS_TO_ENDORSEMENT" -> {
                ApiClient cm = signedInAs(CM);
                confirmEverything(cm, sub);
                passed(cm, sub);
            }
            case "ENDORSE" -> endorsed(vaPoc(), sub);
            case "RETURN_FROM_ENDORSEMENT" -> returned(vaPoc(), sub, "The plan's date is too late.");
            case "REQUEST_EXEMPTION", "REQUEST_SCHEDULED_EXEMPTION" -> {
                JsonNode exemption = requested(signedInAs(SMO_A), at.systemId(),
                        move.equals("REQUEST_EXEMPTION") ? PRIOR_VA_2027 : scheduledVa());
                return at.with(exemption.get("submissionId").asString(), exemption.get("id").asString());
            }
            case "REQUEST_THEN_REJECT" -> {
                return make("REJECT_EXEMPTION", make("REQUEST_EXEMPTION", at));
            }
            case "REQUEST_APPROVE_LAPSE" -> {
                return make("LAPSE", make("APPROVE_EXEMPTION", make("REQUEST_SCHEDULED_EXEMPTION", at)));
            }
            case "APPROVE_EXEMPTION" -> approved(vaPoc(), at.exemptionId());
            case "REJECT_EXEMPTION" -> rejected(vaPoc(), at.exemptionId());
            case "LAPSE" -> {
                JsonNode submission = submission(vaPoc(), sub);
                today(LocalDate.parse(submission.get("conditionalExemption").get("lapsesOn").asString())
                        .plusDays(1).toString());
                lapse();
            }
            case "WITHDRAW" -> {
                ApiClient poc = vaPoc();
                JsonNode preview = preview(poc, file(List.of(listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"),
                        listed("SYS-F3", "File three", SMO_A, CM, "Q3"))));
                var applied = applyConfirming(poc, preview, List.of(sub));
                assertThat(applied.status()).as(applied.body()).isEqualTo(200);
            }
            case "CLOSE_CYCLE" -> {
                today("2028-02-14");
                CycleLifecycleSeam1.closed(vaPoc(), 2027);
            }
            default -> throw new IllegalArgumentException(move);
        }
        return at;
    }

    private static String audited(String move) {
        return switch (move) {
            case "LAPSE", "REQUEST_APPROVE_LAPSE" -> "EXEMPTION_LAPSED";
            case "REQUEST_THEN_REJECT" -> "EXEMPTION_REJECTED";
            case "WITHDRAW" -> "SUBMISSION_WITHDRAWN";
            case "CLOSE_CYCLE" -> "SUBMISSION_NOT_ENDORSED";
            default -> Command.valueOf(move).audited;
        };
    }

    // ---- reading what happened ----

    /** The state SYS-F1's VA Submission is in, or, with none, the state its SMO/Tech Lead's inbox shows for 2027. */
    private String stateOf(At at) {
        return rowOf(at).get("state").asString();
    }

    private boolean overdue(At at) {
        return rowOf(at).get("overdue").asBoolean();
    }

    /** SYS-F1's VA Submission as a VA POC reads it, or, with none, its row in its SMO/Tech Lead's inbox for 2027. */
    private JsonNode rowOf(At at) {
        if (at.submissionId() != null) {
            return submission(vaPoc(), at.submissionId());
        }
        return CycleLifecycleSeam1.itemOf(CycleLifecycleSeam1.inbox(signedInAs(SMO_A), 2027), "SYS-F1");
    }

    /** How many REFUSED entries each action has about this System. */
    private Map<String, Integer> refusedByAction(String systemId) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode entry : AuditEntries.all(otherVaPoc(), "system=" + systemId)) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").has("reason")).as(entry.toString()).isTrue();
                counts.merge(entry.get("action").asString(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private JsonNode newestSuccess(String action, String systemId) {
        for (JsonNode entry : entries(action, systemId)) {
            if (entry.get("outcome").asString().equals("SUCCESS")) {
                return entry;
            }
        }
        throw new AssertionError("no " + action + " entry about " + systemId);
    }
}
