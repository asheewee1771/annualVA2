package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 48: {@code GET /api/dashboards/cm-inbox?cycle=} lists, for the Systems assigned to the caller as Cyber Manager in
 * the System Register, each System's VA Submission (or Not Started) with its state and due dates, Overdue first and then
 * by the day its current window ends, with how many of its Findings the Cyber Manager has confirmed and how many await
 * their review. A VA Submission they acted on at another step is marked: segregation of duties will refuse their review.
 */
class CmInboxTest extends CyberManagerSeam1 {

    @Test
    void aCyberManagerSeesTheSystemsAssignedToThemWithTheirStateAndNothingOfAnotherCyberManagers() {
        registered(listed("SYS-C1", "Mine started", SMO_A, CM, "Q3"),
                listed("SYS-C2", "Mine not started", SMO_A, CM, "Q4"),
                listed("SYS-C3", "Someone else's", SMO_B, CM_OTHER, "Q3"),
                listed("SYS-C4", "Mine as SMO only", CM, CM_OTHER, "Q3"));
        String submission = started(signedInAs(SMO_A), "SYS-C1");

        JsonNode inbox = cmInbox(signedInAs(CM));

        assertThat(inbox.get("cycle").asInt()).isEqualTo(2027);
        assertThat(systemIds(inbox.get("systems"))).containsExactlyInAnyOrder("SYS-C1", "SYS-C2");
        JsonNode draft = item(inbox, "systems", "SYS-C1");
        assertThat(draft.get("name").asString()).isEqualTo("Mine started");
        assertThat(draft.get("quarter").asInt()).isEqualTo(3);
        assertThat(draft.get("state").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("submissionId").asString()).isEqualTo(submission);
        assertThat(draft.get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(draft.get("reviewDue").asString()).isEqualTo("2027-08-29");
        assertThat(draft.get("endorseDue").asString()).isEqualTo("2027-10-31");
        assertThat(draft.get("dueOn").asString()).as("a Draft waits on the Submission Window").isEqualTo("2027-08-15");
        assertThat(draft.get("group").asString()).isEqualTo("WAITING_ON_OTHERS");
        assertThat(draft.get("overdue").asBoolean()).isFalse();
        JsonNode notStarted = item(inbox, "systems", "SYS-C2");
        assertThat(notStarted.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(notStarted.get("submissionId").isNull()).isTrue();
        assertThat(notStarted.get("dueOn").asString()).isEqualTo(notStarted.get("submitDue").asString());
        assertThat(inbox.toString()).doesNotContain("SYS-C3").doesNotContain("Someone else's");
        assertThat(inbox.toString()).as("a System they are only SMO/Tech Lead of").doesNotContain("SYS-C4");
        assertThat(systemIds(cmInbox(signedInAs(CM_OTHER)).get("systems")))
                .containsExactlyInAnyOrder("SYS-C3", "SYS-C4");
    }

    @Test
    void overdueComesFirstAndThenTheDayTheCurrentWindowEnds() {
        registered(listed("SYS-O1", "Draft overdue", SMO_A, CM, "Q3"),
                listed("SYS-O2", "In Review", SMO_A, CM, "Q3"),
                listed("SYS-O3", "Not Started overdue", SMO_A, CM, "Q3"),
                listed("SYS-O4", "Next Quarter", SMO_A, CM, "Q4"));
        ApiClient smo = signedInAs(SMO_A);
        started(smo, "SYS-O1");
        arrange.submissionState(started(smo, "SYS-O2"), "IN_REVIEW");
        today("2027-08-16");
        arrange.overdueSweep();

        JsonNode inbox = cmInbox(signedInAs(CM));

        assertThat(systemIds(inbox.get("systems"))).containsExactly("SYS-O1", "SYS-O3", "SYS-O2", "SYS-O4");
        assertThat(item(inbox, "systems", "SYS-O1").get("overdue").asBoolean()).isTrue();
        assertThat(item(inbox, "systems", "SYS-O3").get("overdue").asBoolean()).as("Not Started too").isTrue();
        JsonNode inReview = item(inbox, "systems", "SYS-O2");
        assertThat(inReview.get("overdue").asBoolean()).isFalse();
        assertThat(inReview.get("dueOn").asString()).as("In Review waits on the Review Window").isEqualTo("2027-08-29");
        assertThat(inReview.get("group").asString()).isEqualTo("NEEDS_YOUR_ACTION");
    }

    @Test
    void eachSystemCountsTheFindingsTheCyberManagerConfirmedAndThoseAwaitingTheirReview() {
        registered(listed("SYS-F1", "With Findings", SMO_A, CM, "Q3"), listed("SYS-F2", "Not started", SMO_A, CM, "Q3"));
        ApiClient smo = signedInAs(SMO_A);
        String submission = started(smo, "SYS-F1");
        List<String> findings = enteredFindings(smo, submission, 4);
        findings.subList(0, 3).forEach(finding -> proposed(smo, finding));
        arrange.findingReview(findings.get(0), "CONFIRMED");
        arrange.findingReview(findings.get(1), "DISPUTED");

        JsonNode inbox = cmInbox(signedInAs(CM));

        JsonNode withFindings = item(inbox, "systems", "SYS-F1");
        assertThat(withFindings.get("confirmed").asInt()).isEqualTo(1);
        assertThat(withFindings.get("awaitingReview").asInt()).as("proposed, not yet confirmed or Disputed").isEqualTo(1);
        JsonNode notStarted = item(inbox, "systems", "SYS-F2");
        assertThat(notStarted.get("confirmed").asInt()).isZero();
        assertThat(notStarted.get("awaitingReview").asInt()).isZero();
    }

    @Test
    void aSubmissionTheCyberManagerActedOnAtAnotherStepIsMarkedForSegregationOfDuties() {
        String switched = "inbox.switched@ci.annualva.test";
        registered(listed("SYS-S1", "Once theirs", switched, CM, "Q3"), listed("SYS-S2", "Never theirs", SMO_A, CM, "Q3"));
        started(signedInAs(switched), "SYS-S1");
        started(signedInAs(SMO_A), "SYS-S2");
        registered(listed("SYS-S1", "Once theirs", SMO_A, switched, "Q3"),
                listed("SYS-S2", "Never theirs", SMO_A, switched, "Q3"));

        JsonNode inbox = cmInbox(signedInAs(switched));

        assertThat(item(inbox, "systems", "SYS-S1").get("sodConflict").asBoolean())
                .as("they started it as its SMO/Tech Lead").isTrue();
        assertThat(item(inbox, "systems", "SYS-S2").get("sodConflict").asBoolean()).isFalse();
    }

    @Test
    void aSubmissionWithTheVaPocsWaitsOnThemAndAFinalOneIsDoneAndListedLastWithNoWindow() {
        registered(listed("SYS-L1", "Draft", SMO_A, CM, "Q3"), listed("SYS-L2", "Endorsed", SMO_A, CM, "Q3"),
                listed("SYS-L3", "Awaiting", SMO_A, CM, "Q3"), listed("SYS-L4", "Frozen", SMO_A, CM, "Q3"));
        ApiClient smo = signedInAs(SMO_A);
        started(smo, "SYS-L1");
        arrange.submissionState(started(smo, "SYS-L2"), "ENDORSED");
        arrange.submissionState(started(smo, "SYS-L3"), "AWAITING_ENDORSEMENT");
        arrange.submissionState(started(smo, "SYS-L4"), "EXEMPTION_REQUESTED");

        JsonNode inbox = cmInbox(signedInAs(CM));

        assertThat(systemIds(inbox.get("systems"))).containsExactly("SYS-L1", "SYS-L3", "SYS-L2", "SYS-L4");
        JsonNode awaiting = item(inbox, "systems", "SYS-L3");
        assertThat(awaiting.get("state").asString()).isEqualTo("AWAITING_ENDORSEMENT");
        assertThat(awaiting.get("dueOn").asString()).isEqualTo("2027-10-31");
        assertThat(awaiting.get("group").asString()).isEqualTo("WAITING_ON_OTHERS");
        JsonNode endorsed = item(inbox, "systems", "SYS-L2");
        assertThat(endorsed.get("state").asString()).isEqualTo("ENDORSED");
        assertThat(endorsed.get("dueOn").isNull()).isTrue();
        assertThat(endorsed.get("group").asString()).isEqualTo("DONE");
        JsonNode frozen = item(inbox, "systems", "SYS-L4");
        assertThat(frozen.get("state").asString()).isEqualTo("EXEMPTION_REQUESTED");
        assertThat(frozen.get("dueOn").isNull()).as("frozen: it waits on no window").isTrue();
        assertThat(frozen.get("group").asString()).isEqualTo("WAITING_ON_OTHERS");
    }

    @Test
    void aSystemInAQuarterHandledOutsideTheAppIsListedApartWithNothingToDo() {
        registered(listed("SYS-H1", "Handled outside", SMO_A, CM, "Q1"), listed("SYS-H2", "In the app", SMO_A, CM, "Q3"));

        JsonNode inbox = cmInbox(signedInAs(CM));

        assertThat(systemIds(inbox.get("systems"))).containsExactly("SYS-H2");
        assertThat(systemIds(inbox.get("handledOutsideApp"))).containsExactly("SYS-H1");
        JsonNode outside = item(inbox, "handledOutsideApp", "SYS-H1");
        assertThat(outside.get("quarter").asInt()).isEqualTo(1);
        assertThat(outside.has("state")).isFalse();
    }

    @Test
    void bothInboxesGiveTheSameDayForEachStateFromTheOneWindowRule() {
        registered(listed("SYS-W1", "Not started", SMO_A, CM, "Q3"), listed("SYS-W2", "Draft", SMO_A, CM, "Q3"),
                listed("SYS-W3", "Returned", SMO_A, CM, "Q3"), listed("SYS-W4", "Awaiting", SMO_A, CM, "Q3"));
        ApiClient smo = signedInAs(SMO_A);
        started(smo, "SYS-W2");
        arrange.submissionState(started(smo, "SYS-W3"), "RETURNED_FOR_REWORK");
        arrange.submissionState(started(smo, "SYS-W4"), "AWAITING_ENDORSEMENT");

        JsonNode mine = inbox(smo);
        JsonNode reviewed = cmInbox(signedInAs(CM));

        assertThat(item(reviewed, "systems", "SYS-W3").get("dueOn").asString())
                .as("Returned for Rework waits on the Review Window").isEqualTo("2027-08-29");
        for (String system : List.of("SYS-W1", "SYS-W2", "SYS-W3", "SYS-W4")) {
            assertThat(item(mine, "systems", system).get("nextActionDueOn").asString()).as(system)
                    .isEqualTo(item(reviewed, "systems", system).get("dueOn").asString());
        }
    }
}
