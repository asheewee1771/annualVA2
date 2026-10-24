package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 49: {@code GET /api/dashboards/quarter-board?cycle=&quarter=} shows one Quarter at once, its Systems grouped in
 * one column per state, from Not Started to Not Endorsed in the order of the spec's state table. A Cyber Manager sees only
 * the Systems assigned to them in the System Register. The Quarter defaults to the one running today.
 */
class QuarterBoardTest extends CyberManagerSeam1 {

    private static final List<String> COLUMNS = List.of("NOT_STARTED", "DRAFT", "IN_REVIEW", "RETURNED_FOR_REWORK",
            "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED", "WITHDRAWN", "NOT_ENDORSED");

    private static List<String> states(JsonNode board) {
        var states = new ArrayList<String>();
        board.get("columns").forEach(column -> states.add(column.get("state").asString()));
        return states;
    }

    private static JsonNode column(JsonNode board, String state) {
        for (JsonNode column : board.get("columns")) {
            if (column.get("state").asString().equals(state)) {
                return column.get("systems");
            }
        }
        throw new AssertionError("no column " + state + " in " + board);
    }

    @Test
    void theQuarterRunningTodayShowsTheCallersSystemsInOneColumnPerState() {
        registered(listed("SYS-B1", "Not started", SMO_A, CM, "Q3"), listed("SYS-B2", "Draft", SMO_A, CM, "Q3"),
                listed("SYS-B3", "In Review", SMO_A, CM, "Q3"), listed("SYS-B4", "Returned", SMO_A, CM, "Q3"),
                listed("SYS-B5", "Awaiting", SMO_A, CM, "Q3"), listed("SYS-B6", "Endorsed", SMO_A, CM, "Q3"),
                listed("SYS-B7", "Exempted", SMO_A, CM, "Q3"), listed("SYS-B8", "Withdrawn", SMO_A, CM, "Q3"),
                listed("SYS-B9", "Next Quarter", SMO_A, CM, "Q4"),
                listed("SYS-B0", "Someone else's", SMO_B, CM_OTHER, "Q3"),
                listed("SYS-BA", "Mine as SMO only", CM, CM_OTHER, "Q3"));
        ApiClient smo = signedInAs(SMO_A);
        String draft = started(smo, "SYS-B2");
        arrange.submissionState(started(smo, "SYS-B3"), "IN_REVIEW");
        arrange.submissionState(started(smo, "SYS-B4"), "RETURNED_FOR_REWORK");
        arrange.submissionState(started(smo, "SYS-B5"), "AWAITING_ENDORSEMENT");
        arrange.submissionState(started(smo, "SYS-B6"), "ENDORSED");
        arrange.submissionState(started(smo, "SYS-B7"), "EXEMPTED");
        arrange.submissionState(started(smo, "SYS-B8"), "WITHDRAWN");

        JsonNode board = board(signedInAs(CM), "");

        assertThat(board.get("cycle").asInt()).isEqualTo(2027);
        assertThat(board.get("quarter").asInt()).isEqualTo(3);
        assertThat(states(board)).containsExactlyElementsOf(COLUMNS);
        assertThat(systemIds(column(board, "NOT_STARTED"))).containsExactly("SYS-B1");
        assertThat(systemIds(column(board, "DRAFT"))).containsExactly("SYS-B2");
        assertThat(systemIds(column(board, "IN_REVIEW"))).containsExactly("SYS-B3");
        assertThat(systemIds(column(board, "RETURNED_FOR_REWORK"))).containsExactly("SYS-B4");
        assertThat(systemIds(column(board, "AWAITING_ENDORSEMENT"))).containsExactly("SYS-B5");
        assertThat(systemIds(column(board, "ENDORSED"))).containsExactly("SYS-B6");
        assertThat(column(board, "EXEMPTION_REQUESTED")).isEmpty();
        assertThat(systemIds(column(board, "EXEMPTED"))).containsExactly("SYS-B7");
        assertThat(systemIds(column(board, "WITHDRAWN"))).containsExactly("SYS-B8");
        assertThat(column(board, "NOT_ENDORSED")).isEmpty();
        assertThat(board.get("handledOutsideApp")).isEmpty();
        assertThat(board.toString()).as("another Quarter, and another Cyber Manager's System").doesNotContain("SYS-B9")
                .doesNotContain("SYS-B0");
        assertThat(board.toString()).as("a System they are only SMO/Tech Lead of").doesNotContain("SYS-BA");
        JsonNode card = column(board, "DRAFT").get(0);
        assertThat(card.get("name").asString()).isEqualTo("Draft");
        assertThat(card.get("submissionId").asString()).isEqualTo(draft);
        assertThat(column(board, "NOT_STARTED").get(0).get("submissionId").isNull()).isTrue();
    }

    @Test
    void eachSystemCarriesItsOverdueFlagItsFindingsByReviewAndTheSegregationOfDutiesMarker() {
        String switched = "board.switched@ci.annualva.test";
        registered(listed("SYS-K1", "Once theirs", switched, CM, "Q3"), listed("SYS-K2", "Overdue", SMO_A, CM, "Q3"));
        started(signedInAs(switched), "SYS-K1");
        ApiClient smo = signedInAs(SMO_A);
        List<String> findings = enteredFindings(smo, started(smo, "SYS-K2"), 2);
        findings.forEach(finding -> proposed(smo, finding));
        arrange.findingReview(findings.get(0), "CONFIRMED");
        registered(listed("SYS-K1", "Once theirs", SMO_A, switched, "Q3"),
                listed("SYS-K2", "Overdue", SMO_A, switched, "Q3"));
        today("2027-08-16");
        arrange.overdueSweep();

        JsonNode drafts = column(board(signedInAs(switched), "?cycle=2027&quarter=3"), "DRAFT");

        assertThat(systemIds(drafts)).containsExactly("SYS-K1", "SYS-K2");
        JsonNode conflicted = drafts.get(0);
        assertThat(conflicted.get("sodConflict").asBoolean()).isTrue();
        JsonNode overdue = drafts.get(1);
        assertThat(overdue.get("overdue").asBoolean()).isTrue();
        assertThat(overdue.get("sodConflict").asBoolean()).isFalse();
        assertThat(overdue.get("confirmed").asInt()).isEqualTo(1);
        assertThat(overdue.get("awaitingReview").asInt()).isEqualTo(1);
    }

    @Test
    void anotherQuarterCanBeAskedForAndOneHandledOutsideTheAppListsItsSystemsApart() {
        registered(listed("SYS-Q1", "First Quarter", SMO_A, CM, "Q1"), listed("SYS-Q4", "Last Quarter", SMO_A, CM, "Q4"));
        ApiClient manager = signedInAs(CM);

        JsonNode last = board(manager, "?quarter=4");
        JsonNode first = board(manager, "?cycle=2027&quarter=1");

        assertThat(last.get("quarter").asInt()).isEqualTo(4);
        assertThat(systemIds(column(last, "NOT_STARTED"))).containsExactly("SYS-Q4");
        assertThat(first.get("quarter").asInt()).isEqualTo(1);
        assertThat(states(first)).containsExactlyElementsOf(COLUMNS);
        first.get("columns").forEach(column -> assertThat(column.get("systems")).isEmpty());
        assertThat(systemIds(first.get("handledOutsideApp"))).containsExactly("SYS-Q1");
    }

    @Test
    void anOlderCycleOpensOnItsLastQuarterAndTheRunningOneOnTodaysQuarter() {
        today("2028-02-07");
        registered(listed("SYS-Y1", "Every year", SMO_A, CM, "Q3"));
        ApiClient manager = signedInAs(CM);

        assertThat(board(manager, "?cycle=2027").get("quarter").asInt()).isEqualTo(4);
        assertThat(board(manager, "").get("quarter").asInt()).as("today is in Q1 of 2028").isEqualTo(1);
    }

    @Test
    void beforeTheFirstLiveCycleStartsTheBoardOpensOnItsFirstLiveQuarter() {
        today("2027-01-15");
        registered(listed("SYS-Y2", "Early", SMO_A, CM, "Q3"));

        JsonNode board = board(signedInAs(CM), "");

        assertThat(board.get("cycle").asInt()).isEqualTo(2027);
        assertThat(board.get("quarter").asInt()).isEqualTo(3);
        assertThat(systemIds(column(board, "NOT_STARTED"))).containsExactly("SYS-Y2");
    }

    @Test
    void aQuarterThatIsNotOneToFourIsRefusedWithoutEchoingItAndACycleTheAppDoesNotRunIsNotFound() {
        registered(listed("SYS-V1", "Valid", SMO_A, CM, "Q3"));
        ApiClient manager = signedInAs(CM);

        var fifth = manager.get(BOARD + "?quarter=5");
        var word = manager.get(BOARD + "?quarter=third");

        assertThat(fifth.status()).isEqualTo(400);
        assertThat(word.status()).isEqualTo(400);
        assertThat(word.body()).doesNotContain("third");
        assertThat(manager.get(BOARD + "?quarter=0").status()).isEqualTo(400);
        assertThat(manager.get(BOARD + "?cycle=2019&quarter=3").status()).isEqualTo(404);
    }

    @Test
    void anSmoAVaPocAnAuditorAndAPersonWithNoRoleAreForbiddenAndAnonymousIsUnauthenticated() {
        registered(listed("SYS-X1", "Access", SMO_A, CM, "Q3"));
        ApiClient auditor = signedInAs("board.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "board.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        var smo = signedInAs(SMO_A).get(BOARD);
        var poc = vaPoc().get(BOARD);

        assertThat(smo.status()).isEqualTo(403);
        assertThat(smo.body()).doesNotContain("SYS-X1");
        assertThat(poc.status()).as("until the VA POC's board, ticket 33").isEqualTo(403);
        assertThat(poc.body()).doesNotContain("SYS-X1");
        assertThat(signedInAs("board.nobody@ci.annualva.test").get(BOARD).status()).isEqualTo(403);
        assertThat(auditor.get(BOARD).status()).isEqualTo(403);
        assertThat(anotherBrowser().get(BOARD).status()).isEqualTo(401);
        assertThat(signedInAs(CM).postJson(BOARD, "{}").status()).isIn(403, 405);
    }
}
