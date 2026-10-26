package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 80a and IM8 ac-3 (ticket 46): a role unused for 90 days lapses, and the person's next request is refused. An
 * SMO/Tech Lead or Cyber Manager role comes back by itself when a Register apply names the person again, when the Quarter
 * of a System they are named on begins, or when work is handed to them: a Return for Rework, or new windows after a
 * conditional Exemption lapses. The default clock is 09:00 on Monday 2 August 2027, the first day of VA Cycle 2027's Q3;
 * its Q4 begins on Monday 1 November 2027.
 */
class RoleLapseTest extends ExemptionSeam1 {

    private static final String LAPSED = "ROLE_LAPSED";
    private static final String RETURNED = "ROLE_RETURNED";
    private static final String SMO_INBOX = "/api/dashboards/smo-inbox";

    /** The dates below count from the grants the Register makes on the first day of Q3; the base class puts the clock back. */
    @BeforeEach
    void onTheFirstDayOfQ3() {
        today("2027-08-02");
    }

    /** One pass of the role lapse job, run by hand after the clock is moved; the VA POCs act first, so theirs stand. */
    private void roleLapse() {
        vaPoc();
        otherVaPoc();
        arrange.roleLapse();
    }

    private static List<String> roles(ApiClient client) {
        List<String> names = new ArrayList<>();
        ApiClient.json(client.get("/api/session")).get("roles").forEach(role -> names.add(role.asString()));
        return names;
    }

    private static JsonNode onlyEntry(JsonNode entries, String role) {
        List<JsonNode> matching = new ArrayList<>();
        entries.forEach(entry -> {
            if (entry.get("detail").get("role").asString().equals(role)) {
                matching.add(entry);
            }
        });
        assertThat(matching).as("one entry for " + role).hasSize(1);
        return matching.getFirst();
    }

    @Test
    void aRoleUnusedForNinetyDaysLapsesAndThePersonsNextRequestIsRefused() {
        Draft draft = draft();
        String system = draft.systemId();
        clock.advance(Duration.ofDays(80));
        assertThat(inboxRow(signedInAs(SMO_B), "SYS-F2").get("state").asString()).as("SMO_B uses the role")
                .isEqualTo("NOT_STARTED");
        clock.advance(Duration.ofDays(9));
        roleLapse();
        assertThat(entries(LAPSED, system)).as("89 days: not yet").isEmpty();
        clock.advance(Duration.ofDays(2));

        roleLapse();
        roleLapse();

        ApiClient smo = signedInAs(SMO_A);
        assertThat(roles(smo)).isEmpty();
        assertThat(smo.get(SMO_INBOX).status()).isEqualTo(403);
        assertThat(read(smo, draft.submissionId()).status()).isEqualTo(403);
        assertThat(roles(signedInAs(CM))).as("never used since it was granted").isEmpty();
        assertThat(roles(signedInAs(SMO_B))).as("used 11 days ago").containsExactly("SMO");

        JsonNode audited = entries(LAPSED, system);
        assertThat(audited).as("once each, however often the job runs").hasSize(2);
        JsonNode smoEntry = onlyEntry(audited, "SMO");
        assertThat(smoEntry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(smoEntry.get("actorId").isNull()).as("the job acts for nobody").isTrue();
        assertThat(smoEntry.get("detail").get("source").asString()).isEqualTo("REGISTER");
        assertThat(smoEntry.get("detail").get("grant_id").asString()).isEqualTo(arrange.grantOf(SMO_A, "SMO"));
        onlyEntry(audited, "CYBER_MANAGER");
        assertThat(entries(LAPSED, idOf("SYS-F2"))).as("SMO_B's stands; CM_OTHER's lapsed").hasSize(1);
    }

    /** Moves the clock 91 days on, past every grant's last use, and runs the job: every SMO/Tech Lead and Cyber Manager lapses. */
    private void ninetyOneDaysLater() {
        clock.advance(Duration.ofDays(91));
        roleLapse();
    }

    @Test
    void aRegisterApplyNamingThePersonAgainReturnsTheirRole() {
        Draft draft = draft();
        ninetyOneDaysLater();
        String pocId = userId(vaPoc());

        registered(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"));

        ApiClient smo = signedInAs(SMO_A);
        assertThat(roles(smo)).containsExactly("SMO");
        assertThat(submission(smo, draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");
        assertThat(roles(signedInAs(CM))).containsExactly("CYBER_MANAGER");
        JsonNode returned = onlyEntry(entries(RETURNED, draft.systemId()), "SMO");
        assertThat(returned.get("detail").get("reason").asString()).isEqualTo("register");
        assertThat(returned.get("detail").get("grant_id").asString()).isEqualTo(arrange.grantOf(SMO_A, "SMO"));
        assertThat(returned.get("actorId").asString()).as("the VA POC who applied it").isEqualTo(pocId);
        assertThat(arrange.grantsOf(SMO_A)).as("the same grant, back").hasSize(1);
    }

    @Test
    void theQuarterOfASystemThePersonIsNamedOnBeginningReturnsTheirRole() {
        registered(listed("SYS-Q4", "Fourth", SMO_A, CM, "Q4"), listed("SYS-F1", "File one", SMO_B, CM_OTHER, "Q3"));
        String q4 = idOf("SYS-Q4");
        today("2027-10-31");
        clock.advance(Duration.ofHours(1));
        roleLapse();
        assertThat(roles(signedInAs(SMO_A))).as("90 days and an hour after the grant").isEmpty();
        today("2027-11-01");

        roleLapse();
        roleLapse();

        assertThat(roles(signedInAs(SMO_A))).as("Q4 has begun").containsExactly("SMO");
        assertThat(roles(signedInAs(CM))).containsExactly("CYBER_MANAGER");
        assertThat(roles(signedInAs(SMO_B))).as("SYS-F1's Quarter began before the lapse").isEmpty();
        JsonNode returned = entries(RETURNED, q4);
        assertThat(returned).as("once each, however often the job runs").hasSize(2);
        JsonNode smo = onlyEntry(returned, "SMO");
        assertThat(smo.get("detail").get("reason").asString()).isEqualTo("quarter-start");
        assertThat(smo.get("actorId").isNull()).as("the job acts for nobody").isTrue();
        assertThat(entries(RETURNED, idOf("SYS-F1"))).isEmpty();
    }

    @Test
    void aGrantWhoseSystemsQuarterBeganWithinNinetyDaysDoesNotLapse() {
        registered(listed("SYS-Q4", "Fourth", SMO_A, CM, "Q4"), listed("SYS-F1", "File one", SMO_B, CM_OTHER, "Q3"));
        today("2027-11-02");

        roleLapse();

        assertThat(roles(signedInAs(SMO_A))).as("unused for 92 days, but Q4 began yesterday").containsExactly("SMO");
        assertThat(roles(signedInAs(SMO_B))).as("SYS-F1's Q3 began 92 days ago").isEmpty();
    }

    @Test
    void aReturnForReworkReturnsTheRoleOfTheSmoItIsHandedTo() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        clock.advance(Duration.ofDays(91));
        ApiClient cm = signedInAs(CM);
        roleLapse();
        assertThat(roles(signedInAs(SMO_A))).isEmpty();
        reviewed(cm, sub, findingIds(cm, sub).get(0), DISPUTED, "Show the upgrade.");

        returned(cm, sub, "One item needs another look.");

        ApiClient smo = signedInAs(SMO_A);
        assertThat(roles(smo)).containsExactly("SMO");
        assertThat(submission(smo, sub).get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        JsonNode entry = onlyEntry(entries(RETURNED, draft.systemId()), "SMO");
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("return-for-rework");
        assertThat(entry.get("actorId").asString()).as("the Cyber Manager who returned it").isEqualTo(userId(cm));
    }

    @Test
    void aSubmitReturnsTheRoleOfTheCyberManagerItIsHandedTo() {
        Draft draft = readyDraft();
        arrange.lapse(arrange.grantOf(CM, "CYBER_MANAGER"));
        assertThat(roles(signedInAs(CM))).isEmpty();

        submitted(draft.smo(), draft.submissionId());

        ApiClient cm = signedInAs(CM);
        assertThat(roles(cm)).containsExactly("CYBER_MANAGER");
        assertThat(submission(cm, draft.submissionId()).get("state").asString()).isEqualTo("IN_REVIEW");
        JsonNode entry = onlyEntry(entries(RETURNED, draft.systemId()), "CYBER_MANAGER");
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("submitted-for-review");
        assertThat(entry.get("actorId").asString()).as("the SMO/Tech Lead who submitted it").isEqualTo(userId(draft.smo()));
    }

    @Test
    void newWindowsAfterAConditionalExemptionLapsesReturnTheRolesOfThoseTheWorkIsHandedTo() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        approved(vaPoc(), requested(draft.smo(), draft.systemId(), scheduledVa()).get("id").asString());
        today("2028-02-01");
        roleLapse();
        assertThat(roles(signedInAs(SMO_A))).isEmpty();
        assertThat(roles(signedInAs(CM))).isEmpty();

        lapse();

        ApiClient smo = signedInAs(SMO_A);
        assertThat(roles(smo)).containsExactly("SMO");
        assertThat(submission(smo, sub).get("state").asString()).isEqualTo("IN_REVIEW");
        ApiClient cm = signedInAs(CM);
        assertThat(roles(cm)).as("back In Review, with its Cyber Manager").containsExactly("CYBER_MANAGER");
        assertThat(submission(cm, sub).get("submitDue").asString()).isEqualTo("2028-02-14");
        JsonNode returned = entries(RETURNED, draft.systemId());
        assertThat(returned).hasSize(2);
        assertThat(onlyEntry(returned, "SMO").get("detail").get("reason").asString()).isEqualTo("exemption-lapse");
        assertThat(onlyEntry(returned, "CYBER_MANAGER").get("actorId").isNull()).as("the job acts for nobody").isTrue();
    }

    @Test
    void anExemptionLapseBackToAwaitingEndorsementReturnsTheSmoButNotTheCyberManager() {
        Draft draft = awaitingEndorsement();
        approved(vaPoc(), requested(draft.smo(), draft.systemId(), scheduledVa()).get("id").asString());
        today("2028-02-01");
        roleLapse();

        lapse();

        assertThat(roles(signedInAs(SMO_A))).as("told, with new windows").containsExactly("SMO");
        assertThat(roles(signedInAs(CM))).as("the VA POCs act next").isEmpty();
        JsonNode returned = entries(RETURNED, draft.systemId());
        assertThat(returned).hasSize(1);
        assertThat(returned.get(0).get("detail").get("role").asString()).isEqualTo("SMO");
    }

    @Test
    void aRejectedExemptionThatPutsItBackInReviewReturnsNobody() {
        Draft draft = inReview();
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();
        arrange.lapse(arrange.grantOf(CM, "CYBER_MANAGER"));

        rejected(vaPoc(), id);

        assertThat(submission(draft.smo(), draft.submissionId()).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(roles(signedInAs(CM))).as("not a submit: the SMO/Tech Lead asked for the Exemption").isEmpty();
        assertThat(entries(RETURNED, draft.systemId())).isEmpty();
    }

    @Test
    void aConditionalExemptionThatLapsesToNotEndorsedReturnsNobody() {
        // Q4 of VA Cycle 2027 ends on 6 February 2028 and the Cycle closes at the end of 5 March 2028, so a Q4 conditional
        // Exemption lapses by 6 May 2028, after the close, to Not Endorsed with no new windows.
        registered(listed("SYS-Q4", "Fourth", SMO_A, CM, "Q4"), listed("SYS-F1", "File one", SMO_B, CM_OTHER, "Q3"));
        String system = idOf("SYS-Q4");
        JsonNode request = requested(signedInAs(SMO_A), system,
                exemption(SCHEDULED_VA, "2028-03-20", "Third-party VA booked with Acme, PO 4472.", null));
        approved(vaPoc(), request.get("id").asString());
        today("2028-03-06");
        arrange.cycleLifecycle();
        today("2028-05-07");
        roleLapse();

        lapse();

        assertThat(submission(vaPoc(), request.get("submissionId").asString()).get("state").asString())
                .isEqualTo("NOT_ENDORSED");
        assertThat(roles(signedInAs(SMO_A))).isEmpty();
        assertThat(entries(RETURNED, system)).isEmpty();
    }
}
