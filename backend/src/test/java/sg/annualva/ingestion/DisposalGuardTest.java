package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * The one deliberate path through the append-only tables (V36 and V44, ADR 0050 item 7), proved on the application's own
 * database connection, as the audit log's append-only test is: whatever the application's code or a bug in it tried, a
 * delete goes through only inside a transaction that names a list a VA POC has approved, and then only for a row of a
 * VA Submission that list names (ticket 62); an update or a truncate never, the Declaration wordings never, and an
 * audit entry only before the approved list's cutoff. Every other removal is a soft delete. What that check reads, the
 * approved list's items and a Finding's VA Submission, cannot change (V45, ticket 66).
 */
class DisposalGuardTest extends DisposalSeam1 {

    /** When the report of the VA Submission prepared on 14 February 2028 was scanned: four days before. */
    private static final String SCANNED_IN_2028 = "2028-02-10T10:00:00+08:00";
    /** A date well after 14 February 2028, for that VA Submission's remediation plan. */
    private static final String LATER_IN_2028 = "2028-12-31";

    /** Puts the VA Submission :sub on the list :list, as the planner would write the item. */
    private static final String ADD_SUBMISSION = "INSERT INTO disposal_item (list_id, kind, target_id, reason) "
            + "VALUES (:list, 'SUBMISSION', :sub, 'CYCLE_RETENTION_ENDED')";
    /** The refusal of an item for a list that is not DRAFT (V45). */
    private static final String NOT_DRAFT = "disposal_item: an item is inserted only into a DRAFT list";

    @Autowired
    private JdbcClient application;

    @Autowired
    private TransactionTemplate transaction;

    /** Every table guarded by an append-only trigger, read from the schema so a new one is covered by default. */
    private List<String> appendOnlyTables() {
        List<String> tables = application.sql("SELECT DISTINCT event_object_table FROM information_schema.triggers "
                + "WHERE trigger_name LIKE '%append_only' ORDER BY 1").query(String.class).list();
        assertThat(tables).contains("audit_entry", "finding_status_proposal", "bulk_batch", "declaration_text");
        return tables;
    }

    /** The append-only tables a disposal opens to the rows of the VA Submissions its list names: all but two. */
    private List<String> historyTables() {
        List<String> tables = new ArrayList<>(appendOnlyTables());
        tables.remove("declaration_text");
        tables.remove("audit_entry");
        return tables;
    }

    /** "trigger on table" for every trigger that runs this function before each row deleted, and on nothing else. */
    private List<String> rowChecks(String function) {
        return application.sql("SELECT trigger_name || ' on ' || event_object_table FROM information_schema.triggers "
                + "WHERE action_orientation = 'ROW' AND action_timing = 'BEFORE' AND event_manipulation = 'DELETE' "
                + "AND action_statement LIKE 'EXECUTE FUNCTION %' || :function || '()' ORDER BY 1")
                .param("function", function).query(String.class).list();
    }

    /** How many rows each of these tables holds now; the suite empties them before every test. */
    private Map<String, Long> rowsIn(List<String> tables) {
        JsonNode rows = arrange.rows();
        Map<String, Long> held = new TreeMap<>();
        tables.forEach(table -> held.put(table, rows.get(table).asLong()));
        return held;
    }

    /** A DRAFT list holding one expired export; its id. */
    private String draftList() {
        exportMade(vaPoc());
        clock.advance(Duration.ofHours(25));
        arrange.exports();
        retention();
        return listIn(vaPoc(), "DRAFT").get("id").asString();
    }

    /** Runs the statement in a transaction that names this list as the disposal it executes. */
    private void asDisposal(String listId, String statement) {
        asDisposal(listId, statement, Map.of());
    }

    private void asDisposal(String listId, String statement, Map<String, ?> parameters) {
        transaction.executeWithoutResult(status -> {
            application.sql("SELECT set_config('annualva.disposal_list', :list, true)").param("list", listId).query()
                    .singleRow();
            application.sql(statement).params(parameters).update();
        });
    }

    /** The newest audit entry is after any cutoff: a transaction naming this approved list cannot delete it. */
    private void keepsTheNewestAuditEntry(String listId, String why) {
        UUID newest = application.sql("SELECT id FROM audit_entry ORDER BY seq DESC LIMIT 1").query(UUID.class).single();

        assertThatThrownBy(() -> asDisposal(listId, "DELETE FROM audit_entry WHERE id = :id", Map.of("id", newest)))
                .as(why).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("outside the approved disposal range");
    }

    /**
     * Gives a Draft a row in every history table, through the calls its people make: two Findings read from a report
     * scanned then and Bulk-set in one batch, a Finding entered by hand and proposed alone, Burp Suite not applicable,
     * a submit (the Declaration), the batch and the justification Disputed by its Cyber Manager, and a Return for
     * Rework.
     */
    private void everyKindOfHistory(Draft draft, ApiClient cm, String scanned, String targetDate) {
        ApiClient smo = draft.smo();
        String sub = draft.submissionId();
        parsedDc(draft, "dc.json", twoFindings(scanned));
        enteredByHandAndNotApplicable(draft);
        JsonNode page = findings(smo, sub);
        List<String> parsed = List.of(byIdentifier(page, "CVE-2027-0001").get("id").asString(),
                byIdentifier(page, "CVE-2027-0002").get("id").asString());
        String batch = BatchReviewSeam1.bulkSet(draft, parsed, remediationPlanned("Upgrade both.", targetDate));
        proposeEvery(draft);
        submitted(smo, sub);
        BatchReviewSeam1.batchReviewed(cm, batch, DISPUTED, "The plan has no owner.");
        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "It has a web console.");
        returned(cm, sub, "Name an owner for the plan.");
    }

    /**
     * SYS-F2's VA Submission in VA Cycle 2028, started by its own SMO/Tech Lead and given every kind of history: another
     * VA Submission, of another System, in the Cycle after the one these tests close, so nothing of it is due in 2031.
     */
    private Draft otherSubmissionInTheCycleAfter() {
        ApiClient smoB = signedInAs(SMO_B);
        String f2 = idOf("SYS-F2");
        var start = start(smoB, f2, 2028);
        assertThat(start.status()).as(start.body()).isEqualTo(201);
        Draft other = new Draft(smoB, f2, ApiClient.json(start).get("id").asString());
        everyKindOfHistory(other, signedInAs(CM_OTHER), SCANNED_IN_2028, LATER_IN_2028);
        return other;
    }

    @Test
    void namingAListThatNobodyApprovedOpensNothing() {
        String draft = draftList();

        for (String table : appendOnlyTables()) {
            assertThatThrownBy(() -> asDisposal(draft, "DELETE FROM " + table + " WHERE false"))
                    .as(table).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> asDisposal(UUID.randomUUID().toString(), "DELETE FROM " + table + " WHERE false"))
                    .as(table).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> asDisposal("not a list", "DELETE FROM " + table + " WHERE false"))
                    .as(table).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }
    }

    @Test
    void anApprovedListOpensDeletesOnlyAndKeepsTheWordingsAndTheAuditEntriesFromItsCutoff() {
        String list = draftList();
        approved(vaPoc(), list);

        assertThatCode(() -> asDisposal(list, "DELETE FROM bulk_batch WHERE false")).doesNotThrowAnyException();
        assertThatThrownBy(() -> asDisposal(list, "UPDATE bulk_batch SET proposed_at = proposed_at"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asDisposal(list, "TRUNCATE submission_return_item"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asDisposal(list, "DELETE FROM declaration_text WHERE false"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        keepsTheNewestAuditEntry(list, "this list disposes of no Cycle, so it has no audit range");
        assertThatThrownBy(() -> application.sql("DELETE FROM bulk_batch WHERE false").update())
                .as("outside the executor's transaction the table is append-only as ever")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void everyHistoryTableADisposalOpensChecksEachRowItDeletes() {
        assertThat(rowChecks("history_row_disposal_listed"))
                .as("each history table has its _disposal_listed trigger, which refuses a row the approved list does "
                        + "not name, and no other table has one")
                .containsExactlyInAnyOrderElementsOf(
                        historyTables().stream().map(table -> table + "_disposal_listed on " + table).toList());
        assertThat(rowChecks("audit_entry_disposal_range")).as("the audit log keeps its cutoff check")
                .containsExactly("audit_entry_disposal_range on audit_entry");
    }

    @Test
    void theRowCheckRefusesATableItDoesNotKnow() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            application.sql("CREATE TEMP TABLE unknown_history (id integer) ON COMMIT DROP").update();
            application.sql("CREATE TRIGGER unknown_history_disposal_listed BEFORE DELETE ON unknown_history "
                    + "FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed()").update();
            application.sql("INSERT INTO unknown_history VALUES (1)").update();
            application.sql("DELETE FROM unknown_history").update();
        })).as("a new history table fails closed until the function names how its rows reach their VA Submission")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("unknown_history is append-only: no disposal check knows its rows");
    }

    /**
     * Counted by table, with no word of how a row reaches its VA Submission: the listed VA Submission is made first and
     * the other second, a delete of all of a table's rows is refused for the row the list does not name, and what the
     * executor then leaves is exactly what the second one added. A check that read the wrong VA Submission for a table
     * would either let that delete through or refuse the executor's own.
     */
    @Test
    void anApprovedListOpensOnlyTheHistoryOfTheVaSubmissionsItNames() {
        List<String> history = historyTables();
        Draft listed = draft();
        everyKindOfHistory(listed, signedInAs(CM), SCANNED, LATER);
        Map<String, Long> ofListed = rowsIn(history);
        today("2028-02-14");
        lifecycle();
        closed(vaPoc(), 2027);
        Draft other = otherSubmissionInTheCycleAfter();
        Map<String, Long> ofBoth = rowsIn(history);
        assertThat(history).as("each VA Submission has every kind of history").allSatisfy(table -> {
            assertThat(ofListed.get(table)).as(table).isPositive();
            assertThat(ofBoth.get(table)).as(table).isGreaterThan(ofListed.get(table));
        });
        today("2031-02-15");
        retention();
        JsonNode list = listIn(vaPoc(), "DRAFT");
        String listId = list.get("id").asString();
        assertThat(items(list)).contains("SUBMISSION " + listed.submissionId() + " CYCLE_RETENTION_ENDED")
                .noneMatch(item -> item.contains(other.submissionId()));

        assertThat(history).allSatisfy(table -> assertThatThrownBy(() -> asDisposal(listId, "DELETE FROM " + table))
                .as("%s, while nobody has approved the list", table)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(table + " is append-only: DELETE is not allowed"));
        approved(vaPoc(), listId);
        assertThat(history).allSatisfy(table -> assertThatThrownBy(() -> asDisposal(listId, "DELETE FROM " + table))
                .as("%s: a statement that reaches a VA Submission the approved list does not name", table)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(table + " is append-only: a row outside the approved disposal list is kept"));
        keepsTheNewestAuditEntry(listId, "the audit log keeps its own check: an entry from the cutoff on");
        assertThat(rowsIn(history)).as("every refused statement removed nothing").isEqualTo(ofBoth);

        retention();

        assertThat(listIn(vaPoc(), "EXECUTED").get("id").asString()).isEqualTo(listId);
        ApiClient reader = vaPoc();
        assertThat(read(reader, listed.submissionId()).status()).as("the listed VA Submission is gone").isEqualTo(404);
        assertThat(read(reader, other.submissionId()).status()).as("the other is still there").isEqualTo(200);
        Map<String, Long> ofOther = new TreeMap<>();
        history.forEach(table -> ofOther.put(table, ofBoth.get(table) - ofListed.get(table)));
        assertThat(rowsIn(history)).as("the listed one's history went with it and the other's is whole")
                .isEqualTo(ofOther);
    }

    /**
     * Ticket 66: the row check reads the approved list's items, so an item is only ever inserted, into a DRAFT list (the
     * planner's insert). Once the list has left DRAFT nothing can be added, and no item is ever changed or removed,
     * whatever the transaction names.
     */
    @Test
    void anItemIsOnlyEverInsertedIntoADraftList() {
        Draft draft = draft();
        String listId = draftList();
        Map<String, Object> onTheList = Map.of("list", UUID.fromString(listId),
                "sub", UUID.fromString(draft.submissionId()));
        Map<String, String> refused = Map.of(
                "UPDATE disposal_item SET kind = 'SUBMISSION', target_id = :sub WHERE list_id = :list",
                "disposal_item is insert-only: UPDATE is not allowed",
                "DELETE FROM disposal_item WHERE list_id = :list", "disposal_item is insert-only: DELETE is not allowed",
                "TRUNCATE disposal_item", "disposal_item is insert-only: TRUNCATE is not allowed");
        refused.forEach((statement, message) -> assertThatThrownBy(() -> asDisposal(listId, statement, onTheList))
                .as("%s, on a DRAFT list", statement).isInstanceOf(DataAccessException.class)
                .hasMessageContaining(message));
        approved(vaPoc(), listId);

        assertThatThrownBy(() -> asDisposal(listId, ADD_SUBMISSION, onTheList)).as("added to the approved list")
                .isInstanceOf(DataAccessException.class).hasMessageContaining(NOT_DRAFT);
        refused.forEach((statement, message) -> assertThatThrownBy(() -> asDisposal(listId, statement, onTheList))
                .as("%s, on the approved list", statement).isInstanceOf(DataAccessException.class)
                .hasMessageContaining(message));
    }

    /**
     * Ticket 66: a transaction naming the approved list that puts a VA Submission on it is refused, so that VA
     * Submission's history is still there, and the executor then disposes of exactly what the VA POC approved.
     */
    @Test
    void theExecutorDisposesOfExactlyWhatWasApproved() {
        List<String> history = historyTables();
        Draft draft = draft();
        everyKindOfHistory(draft, signedInAs(CM), SCANNED, LATER);
        Map<String, Long> held = rowsIn(history);
        String listId = draftList();
        List<String> approvedItems = items(listIn(vaPoc(), "DRAFT"));
        approved(vaPoc(), listId);

        assertThatThrownBy(() -> asDisposal(listId, ADD_SUBMISSION, Map.of("list", UUID.fromString(listId),
                "sub", UUID.fromString(draft.submissionId()))))
                .as("a VA Submission added to the approved list in the executor's own kind of transaction")
                .isInstanceOf(DataAccessException.class).hasMessageContaining(NOT_DRAFT);
        assertThat(rowsIn(history)).as("the history of the VA Submission it named is still there").isEqualTo(held);

        retention();

        JsonNode executed = listIn(vaPoc(), "EXECUTED");
        assertThat(executed.get("id").asString()).isEqualTo(listId);
        assertThat(items(executed)).as("the list disposed of what the VA POC approved").isEqualTo(approvedItems);
        assertThat(read(vaPoc(), draft.submissionId()).status()).isEqualTo(200);
        assertThat(rowsIn(history)).isEqualTo(held);
    }

    /**
     * Ticket 66: a Finding's VA Submission is the hop the row check takes for its proposals and reviews, so it cannot
     * change; the application's other updates of a Finding (its current proposal and review state) are unchanged.
     */
    @Test
    void aFindingStaysWithItsVaSubmission() {
        Draft draft = draft();
        parsedDc(draft, "dc.json", twoFindings(SCANNED));
        proposeEvery(draft);
        String other = started(signedInAs(SMO_B), idOf("SYS-F2")).get("id").asString();

        assertThatThrownBy(() -> application.sql("UPDATE finding SET submission_id = :other WHERE submission_id = :sub")
                .param("other", UUID.fromString(other)).param("sub", UUID.fromString(draft.submissionId())).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finding: a Finding stays with the VA Submission it was found in");
        assertThatCode(() -> application.sql("UPDATE finding SET submission_id = submission_id, review_state = "
                + "review_state WHERE submission_id = :sub").param("sub", UUID.fromString(draft.submissionId())).update())
                .as("an update that leaves the VA Submission as it is").doesNotThrowAnyException();
        assertThat(byIdentifier(findings(draft.smo(), draft.submissionId()), "CVE-2027-0001")).isNotNull();
        assertThat(findings(signedInAs(SMO_B), other).get("items")).as("nothing moved to the other").isEmpty();
    }
}
