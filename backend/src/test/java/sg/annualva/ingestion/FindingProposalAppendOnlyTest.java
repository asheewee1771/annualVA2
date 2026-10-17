package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 35 (ticket 19, ADR 0027): a proposal is never changed or removed, and no row can hold other fields than its status
 * needs, whatever wrote it. Like the audit log's append-only test this goes under the HTTP API, because the claim is about
 * the application's own database connection: whatever its code or a bug in it tried, the database refuses.
 */
class FindingProposalAppendOnlyTest extends ProposalSeam1 {

    @Autowired
    private JdbcClient application;

    private String proposedOne(Draft draft) {
        String id = findingId(draft, "CVE-2021-44228");
        proposed(draft.smo(), id, remediated("Patched."));
        return id;
    }

    private String fingerprint(Draft draft, String findingId) {
        return ApiClient.json(draft.smo().get(proposalsOf(findingId))).toString();
    }

    @Test
    void anUpdateOrADeleteOrATruncateThroughTheApplicationsConnectionFailsAndChangesNothing() {
        Draft draft = draftWithFindings();
        String id = proposedOne(draft);
        String before = fingerprint(draft, id);

        for (String sql : new String[] {"UPDATE finding_status_proposal SET evidence = 'changed'",
                "UPDATE finding_status_proposal SET evidence = 'changed' WHERE id = gen_random_uuid()",
                "DELETE FROM finding_status_proposal", "DELETE FROM finding_status_proposal WHERE id = gen_random_uuid()",
                "TRUNCATE finding_status_proposal CASCADE"}) {
            assertThatThrownBy(() -> application.sql(sql).update()).as(sql).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        assertThat(fingerprint(draft, id)).as("nothing was changed or removed").isEqualTo(before);
    }

    private void insert(String status, String columns, String values) {
        // Direct SQL: the application's own validation would stop a bad row before the table ever saw it.
        UUID finding = application.sql("SELECT id FROM finding LIMIT 1").query(UUID.class).single();
        UUID user = application.sql("SELECT id FROM app_user LIMIT 1").query(UUID.class).single();
        application.sql("INSERT INTO finding_status_proposal (finding_id, status, proposed_by, proposed_at" + columns
                + ") VALUES (:finding, :status, :user, now()" + values + ")")
                .param("finding", finding).param("status", status).param("user", user).update();
    }

    @Test
    void theTableRefusesARowThatLacksTheFieldOfItsStatusOrCarriesAFieldOfAnother() {
        Draft draft = draftWithFindings();
        proposedOne(draft);

        for (String[] bad : new String[][] {
                {"REMEDIATED", "", ""},
                {"REMEDIATED", ", plan", ", 'x'"},
                {"REMEDIATED", ", evidence, target_date", ", 'x', '2030-01-01'"},
                {"FALSE_POSITIVE", ", evidence", ", 'x'"},
                {"RISK_ACCEPTED", ", reason, approver_name", ", 'x', 'y'"},
                {"RISK_ACCEPTED", ", reason, approver_role", ", 'x', 'y'"},
                {"RISK_ACCEPTED", ", approver_name, approver_role", ", 'x', 'y'"},
                {"RISK_ACCEPTED", ", reason, approver_name, approver_role, plan", ", 'x', 'y', 'z', 'p'"},
                {"REMEDIATION_PLANNED", ", plan", ", 'x'"},
                {"REMEDIATION_PLANNED", ", target_date", ", '2030-01-01'"},
                {"REMEDIATION_PLANNED", ", plan, target_date, reason", ", 'x', '2030-01-01', 'r'"},
                {"CONFIRMED", ", evidence", ", 'x'"}}) {
            assertThatThrownBy(() -> insert(bad[0], bad[1], bad[2])).as(bad[0] + bad[1]).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("finding_status_proposal_");
        }
        assertThatThrownBy(() -> insert("REMEDIATED", ", evidence", ", ''")).as("an empty text").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finding_status_proposal_length_check");
        assertThatThrownBy(() -> insert("REMEDIATED", ", evidence", ", repeat('x', 4001)")).as("a text over 4000")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("finding_status_proposal_length_check");
        assertThatThrownBy(() -> insert("RISK_ACCEPTED", ", reason, approver_name, approver_role",
                ", 'x', repeat('n', 201), 'r'")).as("a name over 200").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finding_status_proposal_length_check");
    }

    @Test
    void aFindingHasNoProposalAndNoReviewStateTogetherOrBoth() {
        Draft draft = draftWithFindings();
        proposedOne(draft);

        assertThatThrownBy(() -> application.sql("UPDATE finding SET review_state = 'PENDING' WHERE current_proposal_id IS NULL")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("finding_proposal_review_check");
        assertThatThrownBy(() -> application.sql("UPDATE finding SET review_state = NULL WHERE current_proposal_id IS NOT NULL")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("finding_proposal_review_check");
        assertThatThrownBy(() -> application.sql("UPDATE finding SET review_state = 'APPROVED' WHERE current_proposal_id IS NOT NULL")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("finding_review_state_check");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(byIdentifier(page, "CVE-2021-44228").get("reviewState").asString()).isEqualTo("PENDING");
    }
}
