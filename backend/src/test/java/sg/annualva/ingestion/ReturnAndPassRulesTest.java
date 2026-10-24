package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 30: the rules a return and a pass to endorsement share with every review-step command. Segregation of duties
 * (story 58, ADR 0035), the Overdue flag following the window of the new state (ADR 0033), what is kept of a return and who
 * reads it, and the comment never leaving the response that carries it.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReturnAndPassRulesTest extends ReturnSeam1 {

    @Autowired
    private JdbcClient application;

    @Test
    void aCyberManagerWhoSubmittedTheVaSubmissionAsItsSmoMayNeitherReturnNorPassIt() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        reviewed(signedInAs(CM), sub, findingIds(draft.smo(), sub).getFirst(), DISPUTED, "Show the fix.");
        // Release 2's mid-cycle transfer: Release 1 blocks it while the VA Submission is in flight (ADR 0049).
        registeredMidCycle(listed("SYS-F1", "File one", SMO_B, SMO_A, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"));
        ApiClient formerSmo = signedInAs(SMO_A);

        assertSodConflict(returnForRework(formerSmo, sub, null));
        assertSodConflict(passToEndorsement(formerSmo, sub));

        assertThat(submission(formerSmo, sub).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(events.published()).as("only the submit").hasSize(1);
        for (String action : new String[] {"SUBMISSION_RETURNED", "SUBMISSION_PASSED"}) {
            JsonNode refused = entries(action, draft.systemId());
            assertThat(refused).as(action).hasSize(1);
            assertThat(refused.get(0).get("outcome").asString()).isEqualTo("REFUSED");
            assertThat(refused.get(0).get("submissionId").asString()).isEqualTo(sub);
            assertThat(refused.get(0).get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        }
    }

    @Test
    void theSystemsAssignedSmoMayNotPassItEvenBeforeActing() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, sub);
        arrange.grant("SMO", CM, draft.systemId());

        assertSodConflict(passToEndorsement(cm, sub));

        assertThat(submission(cm, sub).get("state").asString()).isEqualTo("IN_REVIEW");
    }

    @Test
    void aReturnKeepsTheOverdueFlagOfTheReviewWindowAndAPassClearsItForTheEndorsementWindow() {
        String sub = inReview().submissionId();
        List<String> ids = findingIds(signedInAs(CM), sub);
        reviewed(signedInAs(CM), sub, ids.get(0), DISPUTED, "Show the fix.");
        today("2027-08-30");
        arrange.overdueSweep();
        // The sessions of four weeks ago have long expired: each person signs in again.
        ApiClient cm = signedInAs(CM);
        ApiClient smo = signedInAs(SMO_A);
        assertThat(submission(cm, sub).get("overdue").asBoolean()).as("the Review Window passed").isTrue();

        assertThat(returned(cm, sub, null).get("overdue").asBoolean()).as("Returned for Rework waits on the same window")
                .isTrue();
        proposed(smo, ids.get(0), remediated("Upgraded to 2.0."));
        submitted(smo, sub);
        confirmEverything(cm, sub);

        JsonNode passedLate = passed(cm, sub);
        assertThat(passedLate.get("overdue").asBoolean()).as("the Endorsement Window has not passed").isFalse();
        assertThat(passedLate.get("overdueSince").isNull()).isTrue();
    }

    @Test
    void theReturnsAreReadByAnyoneWhoMaySeeTheSystemAndEveryReadIsAudited() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");
        returned(cm, sub, "Look again.");

        assertThat(returns(draft.smo(), sub)).hasSize(1);
        assertThat(returns(vaPoc(), sub)).hasSize(1);
        assertNotFound(signedInAs(SMO_B).get(returnsOf(sub)));
        assertNotFound(signedInAs(CM_OTHER).get(returnsOf(sub)));

        JsonNode viewed = entries("SUBMISSION_RETURNS_VIEWED", draft.systemId());
        assertThat(viewed).as("the owner's read too").hasSize(2);
        assertThat(viewed.get(1).get("actorId").asString()).isEqualTo(userId(draft.smo()));
        assertThat(viewed.get(0).get("submissionId").asString()).isEqualTo(sub);
    }

    @Test
    void theCommentNeverReachesTheLogTheAuditLogOrAnErrorBody(CapturedOutput output) {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String tooLong = returnForRework(cm, sub, SENTINEL + "x".repeat(4001)).body();
        String nothingDisputed = returnForRework(cm, sub, "Redo " + SENTINEL).body();
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");

        returned(cm, sub, "Look again " + SENTINEL);

        assertThat(tooLong + nothingDisputed).as("the error bodies").doesNotContain(SENTINEL);
        assertThat(entries("SUBMISSION_RETURNED", draft.systemId()).toString()).doesNotContain(SENTINEL);
        assertThat(returns(cm, sub).get(0).get("comment").asString()).contains(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("submission-returned");
    }

    @Test
    void aReturnAndWhatItAttachedAreNeverChangedOrRemovedThroughTheApplicationsConnection() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");
        reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "It has a web console.");
        returned(cm, sub, "Look again.");
        String before = returns(cm, sub).toString();

        for (String sql : new String[] {"UPDATE submission_return SET comment = 'changed'",
                "DELETE FROM submission_return", "TRUNCATE submission_return CASCADE",
                "UPDATE submission_return_item SET report_type = NULL", "DELETE FROM submission_return_item",
                "TRUNCATE submission_return_item"}) {
            assertThatThrownBy(() -> application.sql(sql).update()).as(sql)
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }

        assertThat(returns(cm, sub).toString()).isEqualTo(before);
    }
}
