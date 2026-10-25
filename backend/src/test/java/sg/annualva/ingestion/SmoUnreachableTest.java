package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 13 (ticket 39): when the mail relay refuses an SMO/Tech Lead's address, every System the Register names them for
 * is flagged SMO Unreachable with the business date, audited, and shown to the VA POCs in the Register table and on their
 * Quarter board. A later mail the relay accepts for that address, or a Register apply that names another SMO/Tech Lead,
 * clears it. Q3 of VA Cycle 2027: the Submission Window ends on 15 August, the Review Window on 29 August.
 */
class SmoUnreachableTest extends MailSeam1 {

    private static final String BOARD = "/api/dashboards/quarter-board";
    private static final String FLAGGED = "SMO_UNREACHABLE";
    private static final String CLEARED = "SMO_UNREACHABLE_CLEARED";

    /**
     * SYS-U1 (Q3) and SYS-U2 (Q4) name {@link #SMO_A}, SYS-U3 (Q3) names {@link #SMO_B}; the relay refuses SMO_A, and the
     * Overdue sweep of 16 August mails both SMO/Tech Leads of Q3.
     */
    private void smoARefusedOnTheSixteenth() {
        registered(listed("SYS-U1", "Unreachable one", SMO_A, CM, "Q3"),
                listed("SYS-U2", "Unreachable two", SMO_A, CM_OTHER, "Q4"),
                listed("SYS-U3", "Reachable", SMO_B, CM, "Q3"));
        mailRelay.rejectAddress(SMO_A);
        today("2027-08-16");
        arrange.overdueSweep();
        dispatched();
    }

    /** Moving SYS-U2 to Q3 mails SMO_A again. */
    private void movedSysU2ToQ3() {
        registered(listed("SYS-U1", "Unreachable one", SMO_A, CM, "Q3"),
                listed("SYS-U2", "Unreachable two", SMO_A, CM_OTHER, "Q3"),
                listed("SYS-U3", "Reachable", SMO_B, CM, "Q3"));
    }

    @Test
    void aRefusedAddressFlagsEverySystemNamingThatSmoWithTheDateForTheVaPocsAlone() {
        smoARefusedOnTheSixteenth();

        JsonNode register = ApiClient.json(vaPoc().get("/api/systems"));
        assertThat(system(register, "SYS-U1").get("smoUnreachableOn").asString()).isEqualTo("2027-08-16");
        assertThat(system(register, "SYS-U2").get("smoUnreachableOn").asString())
                .as("a System whose own mail was never sent is flagged too").isEqualTo("2027-08-16");
        assertThat(system(register, "SYS-U3").get("smoUnreachableOn").isNull()).isTrue();
        assertThat(mailTo(SMO_B)).as("another address still gets its mail").hasSize(1);

        JsonNode board = ApiClient.json(vaPoc().get(BOARD + "?quarter=3"));
        assertThat(card(board, "SYS-U1").get("smoUnreachableOn").asString()).isEqualTo("2027-08-16");
        assertThat(card(board, "SYS-U3").get("smoUnreachableOn").isNull()).isTrue();

        JsonNode ownView = ApiClient.json(signedInAs(SMO_A).get("/api/systems"));
        assertThat(system(ownView, "SYS-U1").get("smoUnreachableOn").isNull())
                .as("the flag is the VA POCs' to fix").isTrue();
        JsonNode cmBoard = ApiClient.json(signedInAs(CM).get(BOARD + "?quarter=3"));
        assertThat(card(cmBoard, "SYS-U1").get("smoUnreachableOn").isNull()).isTrue();
    }

    @Test
    void eachFlaggedSystemIsAuditedOnceByNobodyWithNoAddressAndARefusalLaterKeepsTheFirstDate() {
        smoARefusedOnTheSixteenth();
        today("2027-08-30");
        movedSysU2ToQ3();
        dispatched();

        assertThat(outboxOf(SMO_A)).extracting(row -> row.get("status").asString())
                .as("the move's mail is refused too").containsExactly("REJECTED", "REJECTED");

        for (String systemId : List.of("SYS-U1", "SYS-U2")) {
            JsonNode entries = entries(FLAGGED, idOf(systemId));
            assertThat(entries).as(systemId).hasSize(1);
            JsonNode entry = entries.get(0);
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("actorId").isNull()).as("the dispatcher acts for nobody").isTrue();
            assertThat(entry.toString()).doesNotContain(SMO_A).doesNotContain("Unreachable one");
        }
        assertThat(entries(FLAGGED, idOf("SYS-U3"))).isEmpty();
        assertThat(system(ApiClient.json(vaPoc().get("/api/systems")), "SYS-U1").get("smoUnreachableOn").asString())
                .isEqualTo("2027-08-16");
    }

    @Test
    void aRefusedAddressThatNoSystemNamesAsItsSmoFlagsNothing() {
        Draft draft = inReview();
        mailRelay.rejectAddress(CM);
        today("2027-08-30");
        arrange.overdueSweep();
        dispatched();

        assertThat(onlyMailOf(CM).get("status").asString()).isEqualTo("REJECTED");
        for (JsonNode system : ApiClient.json(vaPoc().get("/api/systems"))) {
            assertThat(system.get("smoUnreachableOn").isNull()).as(system.get("systemId").asString()).isTrue();
        }
        assertThat(entries(FLAGGED, draft.systemId())).isEmpty();
    }

    @Test
    void aLaterMailTheRelayAcceptsForThatAddressClearsEveryFlagOfItAndIsAudited() {
        smoARefusedOnTheSixteenth();
        mailRelay.acceptAddress(SMO_A);

        movedSysU2ToQ3();
        dispatched();

        assertThat(mailTo(SMO_A)).as("the move's mail went out").isNotEmpty();
        JsonNode register = ApiClient.json(vaPoc().get("/api/systems"));
        assertThat(system(register, "SYS-U1").get("smoUnreachableOn").isNull()).isTrue();
        assertThat(system(register, "SYS-U2").get("smoUnreachableOn").isNull()).isTrue();
        for (String systemId : List.of("SYS-U1", "SYS-U2")) {
            JsonNode entries = entries(CLEARED, idOf(systemId));
            assertThat(entries).as(systemId).hasSize(1);
            assertThat(entries.get(0).get("actorId").isNull()).isTrue();
            assertThat(entries.get(0).get("detail").get("source").asString()).isEqualTo("mail-accepted");
        }
    }

    @Test
    void aRegisterApplyNamingANewSmoClearsThatSystemsFlagOnlyAndIsAuditedForTheVaPoc() {
        smoARefusedOnTheSixteenth();

        registered(listed("SYS-U1", "Unreachable one", SMO_B, CM, "Q3"),
                listed("SYS-U2", "Unreachable two", SMO_A, CM_OTHER, "Q4"),
                listed("SYS-U3", "Reachable", SMO_B, CM, "Q3"));

        ApiClient poc = vaPoc();
        String actor = userId(poc);
        JsonNode register = ApiClient.json(poc.get("/api/systems"));
        assertThat(system(register, "SYS-U1").get("smoUnreachableOn").isNull()).isTrue();
        assertThat(system(register, "SYS-U2").get("smoUnreachableOn").asString())
                .as("still names the refused address").isEqualTo("2027-08-16");
        JsonNode entries = entries(CLEARED, idOf("SYS-U1"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("actorId").asString()).as("the VA POC who applied it").isEqualTo(actor);
        assertThat(entries.get(0).get("detail").get("source").asString()).isEqualTo("register");
        assertThat(entries(CLEARED, idOf("SYS-U2"))).isEmpty();
    }

    private static JsonNode system(JsonNode systems, String systemId) {
        for (JsonNode system : systems) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no System " + systemId + " in " + systems);
    }

    /** The one card of this System on a Quarter board, whatever its column. */
    private static JsonNode card(JsonNode board, String systemId) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode column : board.get("columns")) {
            column.get("systems").forEach(card -> {
                if (card.get("systemId").asString().equals(systemId)) {
                    found.add(card);
                }
            });
        }
        assertThat(found).as("one card of %s", systemId).hasSize(1);
        return found.getFirst();
    }
}
