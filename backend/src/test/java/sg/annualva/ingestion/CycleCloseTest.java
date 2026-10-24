package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RecordedCycleEvents;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import sg.annualva.submission.CycleClosed;
import tools.jackson.databind.JsonNode;

/**
 * Stories 17 and 68 (ticket 41): a VA Cycle closes 4 weeks after its Q4 ends, or earlier when a VA POC closes it after a
 * fresh sign-in; either way every VA Submission that is neither Endorsed nor Exempted, and every System with none, becomes
 * Not Endorsed, so the Cycle's report is final, and no transition is accepted afterwards. SYS-F1 and SYS-F2 are in Q3 of
 * VA Cycle 2027.
 */
class CycleCloseTest extends CycleLifecycleSeam1 {

    private static final String NOT_ENDORSED_ACTION = "SUBMISSION_NOT_ENDORSED";
    private static final String CLOSED_ACTION = "CYCLE_CLOSED";

    @Autowired
    private RecordedCycleEvents cycleEvents;

    @Test
    void aVaPocClosesTheCycleEarlyAndEveryUnfinishedSubmissionAndNotStartedSystemIsNotEndorsed() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        String notStarted = idOf("SYS-F2");
        today("2028-02-14");
        ApiClient poc = vaPoc();
        cycleEvents.reset();
        Set<String> before = auditIds(CLOSED_ACTION);

        JsonNode answered = closed(poc, 2027);

        assertThat(answered.get("year").asInt()).isEqualTo(2027);
        assertThat(answered.get("closesOn").asString()).as("closed early, today").isEqualTo("2028-02-14");
        assertThat(answered.get("closedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(answered.get("notEndorsed").asInt()).isEqualTo(2);
        JsonNode read = submission(poc, sub);
        assertThat(read.get("state").asString()).isEqualTo(NOT_ENDORSED);
        assertThat(read.get("overdue").asBoolean()).as("a final state waits on no window").isFalse();
        JsonNode smoB = inbox(signedInAs(SMO_B), 2027);
        JsonNode f2 = itemOf(smoB, "SYS-F2");
        assertThat(f2.get("state").asString()).isEqualTo(NOT_ENDORSED);
        assertThat(f2.get("submissionId").isNull()).as("a Not Endorsed row of its own").isFalse();
        assertThat(f2.get("group").asString()).isEqualTo("DONE");
        assertThat(f2.get("nextAction").isNull()).isTrue();
        JsonNode list = cycles(poc);
        assertThat(openYears(list)).containsExactly(2028);
        assertThat(cycleOf(list, 2027).get("phase").asString()).isEqualTo("CLOSED");
        assertThat(cycleOf(list, 2027).get("closesOn").asString()).isEqualTo("2028-02-14");

        JsonNode f1Entry = entries(NOT_ENDORSED_ACTION, draft.systemId()).get(0);
        assertThat(f1Entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(f1Entry.get("submissionId").asString()).isEqualTo(sub);
        assertThat(f1Entry.get("actorId").asString()).as("the VA POC who closed it").isEqualTo(userId(poc));
        assertThat(f1Entry.get("detail").get("state_from").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(f1Entry.get("detail").get("state_to").asString()).isEqualTo(NOT_ENDORSED);
        assertThat(f1Entry.get("detail").get("cycle").asInt()).isEqualTo(2027);
        JsonNode f2Entry = entries(NOT_ENDORSED_ACTION, notStarted).get(0);
        assertThat(f2Entry.get("detail").get("state_from").asString()).isEqualTo("NOT_STARTED");
        assertThat(f2Entry.get("submissionId").asString()).isEqualTo(f2.get("submissionId").asString());
        List<JsonNode> closedEntries = writtenSince(CLOSED_ACTION, before);
        assertThat(closedEntries).hasSize(1);
        assertThat(closedEntries.getFirst().get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(closedEntries.getFirst().get("detail").get("cycle").asInt()).isEqualTo(2027);
        assertThat(closedEntries.getFirst().get("detail").get("count").asInt()).isEqualTo(2);

        assertThat(cycleEvents.published()).as("one event for the SMOs' notifications").hasSize(1);
        CycleClosed event = cycleEvents.published().getFirst();
        assertThat(event.cycle()).isEqualTo(2027);
        assertThat(event.closedBy()).isEqualTo(UUID.fromString(userId(poc)));
        assertThat(event.notEndorsed()).extracting(CycleClosed.NotEndorsed::systemId)
                .containsExactlyInAnyOrder(UUID.fromString(draft.systemId()), UUID.fromString(notStarted));
        assertThat(event.notEndorsed()).filteredOn(item -> item.from() == null).hasSize(1);
    }

    @Test
    void everyStateThatIsNeitherEndorsedNorExemptedIsNotEndorsedAndQuartersHandledOutsideAreLeftAlone() {
        Draft draft = draft();
        registered(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"),
                listed("SYS-H1", "Handled one", SMO_A, CM, "Q1"), listed("SYS-X4", "Exempted four", SMO_B, CM, "Q4"),
                listed("SYS-E4", "Endorsed four", SMO_B, CM, "Q4"));
        String requested = draft.submissionId();
        arrange.submissionState(requested, "EXEMPTION_REQUESTED");
        String f2 = started(signedInAs(SMO_B), idOf("SYS-F2")).get("id").asString();
        today("2027-11-01");
        ApiClient smoB = signedInAs(SMO_B);
        String exempted = started(smoB, idOf("SYS-X4")).get("id").asString();
        String endorsed = started(smoB, idOf("SYS-E4")).get("id").asString();
        arrange.submissionState(exempted, "EXEMPTED");
        arrange.submissionState(endorsed, "ENDORSED");
        today("2028-02-14");
        ApiClient poc = vaPoc();

        JsonNode answered = closed(poc, 2027);

        assertThat(answered.get("notEndorsed").asInt()).as("the Exemption Requested and the Draft").isEqualTo(2);
        assertThat(submission(poc, requested).get("state").asString()).isEqualTo(NOT_ENDORSED);
        assertThat(submission(poc, f2).get("state").asString()).isEqualTo(NOT_ENDORSED);
        assertThat(submission(poc, exempted).get("state").asString()).isEqualTo("EXEMPTED");
        assertThat(submission(poc, endorsed).get("state").asString()).isEqualTo("ENDORSED");
        JsonNode smoA = inbox(signedInAs(SMO_A), 2027);
        assertThat(smoA.get("handledOutsideApp").toString()).contains("SYS-H1");
        assertThat(entries(NOT_ENDORSED_ACTION, idOf("SYS-H1"))).as("never Not Endorsed").isEmpty();
        assertThat(entries(NOT_ENDORSED_ACTION, idOf("SYS-F1")).get(0).get("detail").get("state_from").asString())
                .isEqualTo("EXEMPTION_REQUESTED");
    }

    @Test
    void closingNeedsAFreshSignInAndChangesNothingWithoutOne() {
        Draft draft = draft();
        // The step-up is asked before anything is read, so a stale session is refused even before Q4 ends. It is refused
        // on the suite's default day, as the other step-up refusals are, so the audit log's newest entries stay theirs.
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(6));

        var refused = close(poc, 2027);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(ApiClient.json(refused).get("stepUpPath").asString()).isEqualTo("/auth/step-up?intent=cycle-close");
        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");

        SignIn.stepUp(poc, issuer, "/auth/step-up?intent=cycle-close&returnTo=/va-poc/cycle-matrix",
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        poc.withCsrf();
        assertConflict(close(poc, 2027), "invalid-transition");

        today("2028-02-14");
        ApiClient signedInNow = vaPoc();
        assertThat(openYears(cycles(signedInNow))).as("nothing was closed").containsExactly(2027, 2028);
        assertThat(closed(signedInNow, 2027).get("notEndorsed").asInt()).isEqualTo(2);
    }

    @Test
    void onlyAVaPocClosesOnlyACycleWhoseQ4HasEndedAndOnlyOnce() {
        Draft draft = draft();
        today("2028-02-06");
        ApiClient poc = vaPoc();
        Set<String> before = auditIds(CLOSED_ACTION);

        assertConflict(close(poc, 2027), "invalid-transition");
        assertThat(writtenSince(CLOSED_ACTION, before).getFirst().get("detail").get("reason").asString())
                .isEqualTo("cycle-running");
        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");

        today("2028-02-14");
        poc = vaPoc();
        assertThat(close(signedInAs(SMO_A), 2027).status()).as("not a VA POC").isEqualTo(403);
        assertNotFound(close(poc, 2026));
        assertNotFound(close(poc, 2029));
        closed(poc, 2027);
        assertConflict(close(otherVaPoc(), 2027), "invalid-transition");
        List<JsonNode> written = writtenSince(CLOSED_ACTION, before);
        assertThat(written).extracting(entry -> entry.get("outcome").asString())
                .containsExactly("REFUSED", "SUCCESS", "REFUSED");
        assertThat(written.getFirst().get("detail").get("reason").asString()).isEqualTo("cycle-closed");
    }

    @Test
    void afterTheCloseNoTransitionIsAccepted() {
        Draft draft = awaitingEndorsement();
        today("2028-02-14");
        ApiClient poc = vaPoc();
        closed(poc, 2027);

        assertConflict(endorse(otherVaPoc(), draft.submissionId()), "invalid-transition");
        var start = start(signedInAs(SMO_B), idOf("SYS-F2"), 2027);
        assertConflict(start, "invalid-transition");
        assertThat(entries("SUBMISSION_STARTED", idOf("SYS-F2")).get(0).get("detail").get("reason").asString())
                .isEqualTo("cycle-closed");
    }

    @Test
    void afterTheCloseDateNoTransitionIsAcceptedEvenBeforeTheJobRuns() {
        Draft draft = awaitingEndorsement();
        today("2028-03-06");

        assertConflict(endorse(vaPoc(), draft.submissionId()), "invalid-transition");

        JsonNode refused = entries("SUBMISSION_ENDORSED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("cycle-closed");
        assertThat(submission(vaPoc(), draft.submissionId()).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
    }

    @Test
    void theJobClosesTheCycleTheDayAfterItsCloseDateForNobodyAndOnce() {
        Draft draft = awaitingEndorsement();
        today("2028-03-05");
        lifecycle();
        assertThat(submission(vaPoc(), draft.submissionId()).get("state").asString()).as("open through its close date")
                .isEqualTo(AWAITING_ENDORSEMENT);
        cycleEvents.reset();
        Set<String> before = auditIds(CLOSED_ACTION);

        today("2028-03-06");
        lifecycle();
        lifecycle();

        ApiClient poc = vaPoc();
        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo(NOT_ENDORSED);
        JsonNode cycle = cycleOf(cycles(poc), 2027);
        assertThat(cycle.get("closesOn").asString()).as("closed on its own date").isEqualTo("2028-03-05");
        assertThat(cycle.get("closedAt").asString()).isEqualTo(clock.instant().toString());
        List<JsonNode> closedEntries = writtenSince(CLOSED_ACTION, before);
        assertThat(closedEntries).as("once, however often the job runs").hasSize(1);
        assertThat(closedEntries.getFirst().get("actorId").isNull()).as("the job acts for nobody").isTrue();
        assertThat(entries(NOT_ENDORSED_ACTION, draft.systemId()).get(0).get("actorId").isNull()).isTrue();
        assertThat(cycleEvents.published()).hasSize(1);
        assertThat(cycleEvents.published().getFirst().closedBy()).isNull();
    }
}
