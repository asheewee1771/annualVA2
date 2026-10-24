package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.ApiClient;

/**
 * Ticket 28 (V21): a review is history, never changed or removed, and no row can be a Dispute without its comment or the
 * review of another Finding's proposal, whatever wrote it. Like the proposals' append-only test this goes under the HTTP
 * API, because the claim is about the application's own database connection.
 */
class ReviewAppendOnlyTest extends ReviewSeam1 {

    @Autowired
    private JdbcClient application;

    @Test
    void anUpdateOrADeleteOrATruncateOfAReviewThroughTheApplicationsConnectionFailsAndChangesNothing() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        reviewed(cm, sub, finding, DISPUTED, "Show the fix.");
        reportReviewed(cm, sub, BURP_SUITE, CONFIRMED, null);
        String before = findingById(cm, sub, finding).toString() + report(cm, sub, BURP_SUITE);

        for (String table : new String[] {"finding_review", "report_review"}) {
            // CASCADE: the returns (V130) refer to the reviews, and a plain TRUNCATE is refused for that before any trigger.
            for (String sql : new String[] {"UPDATE " + table + " SET comment = 'changed'", "DELETE FROM " + table,
                    "TRUNCATE " + table + " CASCADE"}) {
                assertThatThrownBy(() -> application.sql(sql).update()).as(sql)
                        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            }
        }

        assertThat(findingById(cm, sub, finding).toString() + report(cm, sub, BURP_SUITE)).isEqualTo(before);
    }

    @Test
    void aDisputeNeedsItsCommentAndAReviewIsOfAProposalOfItsOwnFinding() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        var ids = findingIds(draft.smo(), sub);
        UUID first = UUID.fromString(ids.get(0));
        UUID otherProposal = UUID.fromString(currentProposalId(draft.smo(), sub, ids.get(1)));
        UUID ownProposal = UUID.fromString(currentProposalId(draft.smo(), sub, ids.get(0)));
        UUID user = UUID.fromString(userId(draft.smo()));

        assertThatThrownBy(() -> insertReview(first, ownProposal, "DISPUTED", null, user)).as("a Dispute with no comment")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("finding_review_comment_check");
        assertThatThrownBy(() -> insertReview(first, ownProposal, "PENDING", null, user)).as("not a decision")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("finding_review_decision_check");
        assertThatThrownBy(() -> insertReview(first, otherProposal, "CONFIRMED", null, user))
                .as("another Finding's proposal").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finding_review_proposal_fkey");
    }

    private void insertReview(UUID finding, UUID proposal, String decision, String comment, UUID user) {
        // Direct SQL: the application's own checks would stop a bad row before the table ever saw it.
        application.sql("INSERT INTO finding_review (finding_id, proposal_id, decision, comment, reviewed_by, reviewed_at) "
                + "VALUES (:finding, :proposal, :decision, CAST(:comment AS text), :user, now())")
                .param("finding", finding).param("proposal", proposal).param("decision", decision)
                .param("comment", comment).param("user", user).update();
    }
}
