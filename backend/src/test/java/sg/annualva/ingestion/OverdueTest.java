package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 42 and 71 (ticket 26): when a window passes without the next step, the scheduled sweep flags the VA Submission,
 * or the Not Started System, Overdue and records when; nothing locks. Q3 of VA Cycle 2027 starts on Monday 2 August 2027,
 * so its Submission Window ends on Sunday 15 August, its Review Window on Sunday 29 August and the Quarter on Sunday
 * 31 October (Singapore business dates). The sweep is run by hand after the clock is moved, as the scheduler would.
 */
class OverdueTest extends SubmitSeam1 {

    private static final String OVERDUE = "SUBMISSION_OVERDUE";

    /** A pass of the sweep at 09:00 Singapore time on this business day. */
    private void sweepOn(String businessDay) {
        today(businessDay);
        arrange.overdueSweep();
    }

    @Test
    void aDraftIsFlaggedOverdueOnceItsSubmissionWindowHasPassedAndTheFlagIsAuditedWithWhenItWasSet() {
        Draft draft = draft();
        String sub = draft.submissionId();

        sweepOn("2027-08-15");
        JsonNode lastDay = submission(signedInAs(SMO_A), sub);
        sweepOn("2027-08-16");
        JsonNode week3 = submission(signedInAs(SMO_A), sub);

        assertThat(lastDay.get("overdue").asBoolean()).as("the window's last day is still inside it").isFalse();
        assertThat(lastDay.get("overdueSince").isNull()).isTrue();
        assertThat(week3.get("state").asString()).isEqualTo("DRAFT");
        assertThat(week3.get("overdue").asBoolean()).isTrue();
        assertThat(week3.get("overdueSince").asString()).isEqualTo(clock.instant().toString());
        JsonNode entries = entries(OVERDUE, draft.systemId());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("submissionId").asString()).isEqualTo(sub);
        assertThat(entry.get("actorId").isNull()).as("the sweep acts for nobody").isTrue();
        assertThat(entry.get("occurredAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(entry.get("detail").propertyNames()).containsExactly("state");
        assertThat(entry.get("detail").get("state").asString()).isEqualTo("DRAFT");
    }

    @Test
    void theWindowEndsAtMidnightInSingaporeNotInUtc() {
        Draft draft = draft();

        clock.set(Instant.parse("2027-08-15T23:30:00+08:00"));
        arrange.overdueSweep();
        boolean lastMinutes = !entries(OVERDUE, draft.systemId()).isEmpty();
        clock.set(Instant.parse("2027-08-16T00:30:00+08:00"));
        arrange.overdueSweep();

        assertThat(lastMinutes).isFalse();
        assertThat(entries(OVERDUE, draft.systemId())).as("still 15 August in UTC, but the 16th in Singapore").hasSize(1);
    }

    @Test
    void theDraftOfARetiredSystemIsNeverFlagged() {
        Draft draft = draft();
        String sub = draft.submissionId();
        // A retired System's Draft is withdrawn since ticket 43; one left over from before stays out of the sweep.
        registeredMidCycle(listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3"));

        sweepOn("2027-08-16");

        assertThat(submission(vaPoc(), sub).get("overdue").asBoolean())
                .as("SYS-F1 was left out of the Register, so nobody acts on its Draft").isFalse();
        assertThat(entries(OVERDUE, draft.systemId())).isEmpty();
    }

    @Test
    void runningTheSweepAgainChangesNothingMore() {
        Draft draft = draft();
        sweepOn("2027-08-16");
        String since = submission(signedInAs(SMO_A), draft.submissionId()).get("overdueSince").asString();

        clock.advance(Duration.ofHours(1));
        arrange.overdueSweep();
        sweepOn("2027-08-30");

        assertThat(submission(signedInAs(SMO_A), draft.submissionId()).get("overdueSince").asString()).isEqualTo(since);
        assertThat(entries(OVERDUE, draft.systemId())).hasSize(1);
    }

    @Test
    void aVaSubmissionInReviewIsFlaggedOnceItsReviewWindowHasPassed() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        submitted(draft.smo(), sub);

        sweepOn("2027-08-16");
        sweepOn("2027-08-29");
        boolean lastDayOfReview = overdue(signedInAs(SMO_A), sub);
        sweepOn("2027-08-30");

        assertThat(lastDayOfReview).as("past the Submission Window, but In Review waits on the Review Window").isFalse();
        JsonNode read = submission(signedInAs(CM), sub);
        assertThat(read.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(read.get("overdue").asBoolean()).as("as the Cyber Manager reads it").isTrue();
        assertThat(read.get("overdueSince").asString()).isEqualTo(clock.instant().toString());
        JsonNode entries = entries(OVERDUE, draft.systemId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("detail").get("state").asString()).isEqualTo("IN_REVIEW");
    }

    @Test
    void aVaSubmissionReturnedForReworkIsFlaggedOnlyOnceTheReviewWindowHasPassed() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        submitted(draft.smo(), sub);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");

        sweepOn("2027-08-16");
        sweepOn("2027-08-29");
        boolean week4 = overdue(signedInAs(SMO_A), sub);
        sweepOn("2027-08-30");

        assertThat(week4).as("its Submission Window has passed, but rework is due with the review").isFalse();
        assertThat(overdue(signedInAs(SMO_A), sub)).isTrue();
        JsonNode entries = entries(OVERDUE, draft.systemId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("detail").get("state").asString()).isEqualTo("RETURNED_FOR_REWORK");
    }

    @Test
    void awaitingEndorsementIsFlaggedAfterTheQuartersLastDayAndFinalOrFrozenStatesNeverAre() {
        Draft draft = draft();
        String sub = draft.submissionId();

        for (String state : List.of("ENDORSED", "EXEMPTED", "WITHDRAWN", "NOT_ENDORSED", "EXEMPTION_REQUESTED")) {
            arrange.submissionState(sub, state);
            sweepOn("2027-11-01");
        }
        boolean finalStates = !entries(OVERDUE, draft.systemId()).isEmpty();
        arrange.submissionState(sub, "AWAITING_ENDORSEMENT");
        sweepOn("2027-10-31");
        boolean lastDay = !entries(OVERDUE, draft.systemId()).isEmpty();
        sweepOn("2027-11-01");

        assertThat(finalStates).as("nothing is expected of a final or frozen VA Submission").isFalse();
        assertThat(lastDay).as("endorsement is due on the Quarter's last day").isFalse();
        JsonNode entries = entries(OVERDUE, draft.systemId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(entries.get(0).get("detail").get("state").asString()).isEqualTo("AWAITING_ENDORSEMENT");
    }

    @Test
    void anOverdueDraftCanStillUploadProposeAndSubmitAndIsNotOverdueInReviewWhileTheReviewWindowRuns() {
        Draft started = draft();
        String sub = started.submissionId();
        sweepOn("2027-08-16");
        Draft draft = new Draft(signedInAs(SMO_A), started.systemId(), sub);
        boolean flagged = overdue(draft.smo(), sub);

        parsedDc(draft, "dc.json", dcReportAt(SCANNED));
        modeSet(draft.smo(), sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "The System has no web interface."));
        proposeEvery(draft);
        JsonNode answered = submitted(draft.smo(), sub);
        sweepOn("2027-08-29");

        assertThat(flagged).isTrue();
        assertThat(answered.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(answered.get("overdue").asBoolean()).as("its current window is the Review Window").isFalse();
        assertThat(answered.get("overdueSince").isNull()).isTrue();
        assertThat(overdue(signedInAs(SMO_A), sub)).as("not until the Review Window has passed").isFalse();
        assertThat(entries(OVERDUE, draft.systemId())).hasSize(1);
    }

    @Test
    void anOverdueDraftSubmittedAfterTheReviewWindowStaysOverdueFromWhenItWasFlagged() {
        Draft ready = readyDraft();
        String sub = ready.submissionId();
        sweepOn("2027-08-16");
        String since = clock.instant().toString();
        today("2027-08-30");

        JsonNode answered = submitted(signedInAs(SMO_A), sub);
        arrange.overdueSweep();

        assertThat(answered.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(answered.get("overdue").asBoolean()).isTrue();
        assertThat(answered.get("overdueSince").asString()).isEqualTo(since);
        assertThat(submission(signedInAs(SMO_A), sub).get("overdueSince").asString()).isEqualTo(since);
        assertThat(entries(OVERDUE, ready.systemId())).as("the flag was never cleared, so it is not set again")
                .hasSize(1);
    }

    @Test
    void aNotStartedSystemIsFlaggedOnceItsSubmissionWindowHasPassedAndShownOverdueInTheInbox() {
        registered(
                List.of(listed("SYS-N3", "Not started", SMO_A, CM, "Q3"), listed("SYS-N4", "Later Quarter", SMO_A, CM, "Q4"),
                        listed("SYS-N1", "Handled outside", SMO_A, CM, "Q1")),
                List.of(listed("SYS-N5", "Retired", SMO_A, CM, "Q3")));
        String n3 = idOf("SYS-N3");
        String n1 = idOf("SYS-N1");
        String n4 = idOf("SYS-N4");
        String n5 = idOf("SYS-N5");

        sweepOn("2027-08-15");
        JsonNode lastDay = inbox(signedInAs(SMO_A));
        sweepOn("2027-08-16");
        arrange.overdueSweep();
        JsonNode week3 = inbox(signedInAs(SMO_A));

        assertThat(inboxItem(lastDay, "SYS-N3").get("overdue").asBoolean()).isFalse();
        assertThat(inboxItem(week3, "SYS-N3").get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(inboxItem(week3, "SYS-N3").get("overdue").asBoolean()).isTrue();
        assertThat(inboxItem(week3, "SYS-N4").get("overdue").asBoolean()).as("its window is still to come").isFalse();
        assertThat(week3.get("handledOutsideApp").get(0).has("overdue")).as("nothing is expected of it here").isFalse();
        JsonNode entries = entries(OVERDUE, n3);
        assertThat(entries).as("once, however often the sweep runs").hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("submissionId").isNull()).as("there is no VA Submission yet").isTrue();
        assertThat(entry.get("actorId").isNull()).isTrue();
        assertThat(entry.get("occurredAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(entry.get("detail").get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(entry.toString()).as("ids and fixed codes only").doesNotContain("SYS-N3").doesNotContain("Not started");
        for (String skipped : List.of(n1, n4, n5)) {
            assertThat(entries(OVERDUE, skipped)).as("Handled outside the app, not due yet, or retired").isEmpty();
        }
    }

    @Test
    void aNotStartedSystemIsFlaggedWhileItsCycleIsClosingButNotOnceTheCycleHasClosed() {
        registered(listed("SYS-N3", "Not started", SMO_A, CM, "Q3"));
        String n3 = idOf("SYS-N3");

        sweepOn("2028-03-06");
        boolean closed = !entries(OVERDUE, n3).isEmpty();
        sweepOn("2028-03-05");

        assertThat(closed).as("VA Cycle 2027 closed on 5 March 2028; nothing more is flagged in it").isFalse();
        assertThat(entries(OVERDUE, n3)).as("on its last day it is still closing").hasSize(1);
    }

    @Test
    void aVaSubmissionStartedForAnOverdueNotStartedSystemIsAnOverdueDraftFromWhenTheSystemWasFlagged() {
        registered(listed("SYS-N3", "Not started", SMO_A, CM, "Q3"));
        String n3 = idOf("SYS-N3");
        sweepOn("2027-08-16");
        String flaggedAt = clock.instant().toString();
        clock.advance(Duration.ofDays(1));
        ApiClient smo = signedInAs(SMO_A);

        JsonNode started = started(smo, n3);
        arrange.overdueSweep();

        assertThat(started.get("state").asString()).isEqualTo("DRAFT");
        assertThat(started.get("overdue").asBoolean()).isTrue();
        assertThat(started.get("overdueSince").asString()).as("the flag goes with it").isEqualTo(flaggedAt);
        JsonNode item = inboxItem(inbox(smo), "SYS-N3");
        assertThat(item.get("state").asString()).isEqualTo("DRAFT");
        assertThat(item.get("overdue").asBoolean()).isTrue();
        assertThat(entries(OVERDUE, n3)).as("it was set once, on the System").hasSize(1);
    }

    private static JsonNode inbox(ApiClient client) {
        var response = client.get("/api/dashboards/smo-inbox");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    private static JsonNode inboxItem(JsonNode inbox, String systemId) {
        for (JsonNode item : inbox.get("systems")) {
            if (item.get("systemId").asString().equals(systemId)) {
                return item;
            }
        }
        throw new AssertionError("no System " + systemId + " in " + inbox);
    }

    private static boolean overdue(ApiClient client, String submissionId) {
        return submission(client, submissionId).get("overdue").asBoolean();
    }
}
