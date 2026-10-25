package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.CapturingMailRelay.Sent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 88, 89 and 90 (ticket 38): the daily planner reminds whoever's step it is before and on the last day of the
 * window their VA Submission (or Not Started System) waits on, then weekly while it is Overdue with a copy to the next
 * person up, and reminds both VA POCs 2 weeks and 1 week before the Quarter's last day. Q3 of VA Cycle 2027: the
 * Submission Window ends on Sunday 15 August, the Review Window on Sunday 29 August and the Quarter on Sunday 31 October.
 * SYS-F1 (SMO_A, CM) has a VA Submission; SYS-F2 (SMO_B, CM_OTHER) is Not Started.
 */
class DeadlineReminderTest extends MailSeam1 {

    private static final Listed F2 = listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3");
    private static final String DUE_SOON_TEMPLATE = "ci-reminder-due-soon";
    private static final String DUE_TEMPLATE = "ci-reminder-due";
    private static final String WEEKLY_TEMPLATE = "ci-overdue-weekly";
    private static final String COPY_TEMPLATE = "ci-reminder-copy";

    /** The planner's pass on this business day, at 09:00 Singapore time. */
    private void plannedOn(String businessDay) {
        today(businessDay);
        arrange.reminders();
    }

    private List<String> templatesTo(String email) {
        return mailTo(email).stream().map(Sent::templateId).toList();
    }

    @Test
    void fiveDaysBeforeTheSubmissionWindowEndsTheSmoOfADraftAndOfANotStartedSystemIsRemindedOnce() {
        Draft draft = draft();
        String sub = draft.submissionId();

        plannedOn("2027-08-09");
        assertThat(actions(signedInAs(SMO_A))).as("six days before: nothing yet").isEmpty();
        plannedOn("2027-08-10");
        arrange.reminders();

        JsonNode item = only(signedInAs(SMO_A), "SUBMIT_DUE_SOON");
        assertThat(item.get("state").asString()).isEqualTo("DRAFT");
        assertThat(item.get("submissionId").asString()).isEqualTo(sub);
        assertThat(item.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(item.get("link").asString()).isEqualTo("/smo/submissions/" + sub);
        assertThat(item.get("message").asString()).isEqualTo("Due in 5 days: prepare and submit the VA Submission.");
        JsonNode notStarted = only(signedInAs(SMO_B), "SUBMIT_DUE_SOON");
        assertThat(notStarted.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(notStarted.get("submissionId").isNull()).isTrue();
        assertThat(notStarted.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(notStarted.get("link").asString()).isEqualTo("/smo");
        assertThat(actions(signedInAs(CM))).as("no copy before the window has passed").isEmpty();
        assertThat(outboxOf(SMO_A)).as("a rerun on the same day queues nothing more").hasSize(1);

        dispatched();

        assertThat(templatesTo(SMO_A)).containsExactly(DUE_SOON_TEMPLATE);
        assertThat(mailTo(SMO_A).getFirst().values()).containsEntry("due_date", "15 August 2027")
                .containsEntry("action", "Due in 5 days: prepare and submit the VA Submission.")
                .containsEntry("link", APP_URL + "/smo/submissions/" + sub);
    }

    @Test
    void onTheLastDayAndThenWeeklyWhileOverdueTheSmoIsRemindedAndTheCyberManagerGetsEachWeeklyOneAsAMailOfItsOwn() {
        Draft draft = draft();

        plannedOn("2027-08-15");
        today("2027-08-16");
        arrange.overdueSweep();
        arrange.reminders();
        plannedOn("2027-08-22");
        plannedOn("2027-08-23");
        plannedOn("2027-08-29");
        plannedOn("2027-08-30");

        List<String> weekly =
                List.of("SUBMIT_STILL_OVERDUE", "SUBMIT_STILL_OVERDUE", "SUBMIT_OVERDUE", "SUBMIT_DUE_TODAY");
        assertThat(actions(signedInAs(SMO_A))).as("newest first: the Overdue message, then a reminder each week")
                .containsExactlyElementsOf(weekly);
        assertThat(actions(signedInAs(SMO_B))).as("a Not Started System alike").containsExactlyElementsOf(weekly);
        ApiClient cm = signedInAs(CM);
        assertThat(actions(cm)).as("a copy of each weekly reminder, of their own System only")
                .containsExactly("SUBMIT_OVERDUE_COPY", "SUBMIT_OVERDUE_COPY");
        JsonNode copy = notifications(cm).get(0);
        assertThat(copy.get("systemCode").asString()).isEqualTo("SYS-F1");
        assertThat(copy.get("state").asString()).isEqualTo("DRAFT");
        assertThat(copy.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(copy.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(copy.get("link").asString()).isEqualTo("/cyber-manager");
        assertThat(copy.get("message").asString())
                .isEqualTo("Copy for you: the SMO/Tech Lead's step on this VA Submission is still Overdue.");
        assertThat(actions(signedInAs(CM_OTHER))).containsExactly("SUBMIT_OVERDUE_COPY", "SUBMIT_OVERDUE_COPY");

        dispatched();

        assertThat(templatesTo(SMO_A)).containsExactly(DUE_TEMPLATE, OVERDUE_TEMPLATE, WEEKLY_TEMPLATE, WEEKLY_TEMPLATE);
        assertThat(templatesTo(CM)).containsExactly(COPY_TEMPLATE, COPY_TEMPLATE);
        assertThat(mailTo(CM).getFirst().values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "system_id", "SYS-F1",
                "system_name", "File one",
                "state", "Draft",
                "action", "Copy for you: the SMO/Tech Lead's step on this VA Submission is still Overdue.",
                "due_date", "15 August 2027",
                "link", APP_URL + "/cyber-manager"));
    }

    @Test
    void theWeeklyReminderReadsTheOverdueFlagAndSoWaitsForTheSweepToSetIt() {
        draft();

        plannedOn("2027-08-23");

        assertThat(actions(signedInAs(SMO_A))).isEmpty();
        assertThat(actions(signedInAs(SMO_B))).isEmpty();
        assertThat(actions(signedInAs(CM))).isEmpty();
    }

    @Test
    void theCyberManagerIsRemindedAgainstTheReviewWindowAndEachVaPocGetsACopyOfTheWeeklyOne() {
        Draft draft = inReview();
        String sub = draft.submissionId();

        plannedOn("2027-08-24");
        plannedOn("2027-08-29");
        today("2027-08-30");
        arrange.overdueSweep();
        plannedOn("2027-09-05");
        plannedOn("2027-09-06");

        ApiClient cm = signedInAs(CM);
        assertThat(actions(cm)).containsExactly("REVIEW_STILL_OVERDUE", "REVIEW_OVERDUE", "REVIEW_DUE_TODAY",
                "REVIEW_DUE_SOON", "REVIEW");
        JsonNode soon = only(cm, "REVIEW_DUE_SOON");
        assertThat(soon.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(soon.get("dueOn").asString()).isEqualTo("2027-08-29");
        assertThat(soon.get("link").asString()).isEqualTo("/cyber-manager/submissions/" + sub);
        assertThat(soon.get("message").asString()).isEqualTo("Due in 5 days: review the VA Submission.");
        for (ApiClient poc : List.of(vaPoc(), otherVaPoc())) {
            JsonNode copy = only(poc, "REVIEW_OVERDUE_COPY");
            assertThat(copy.get("link").asString()).isEqualTo("/va-poc/submissions/" + sub);
            assertThat(copy.get("dueOn").asString()).isEqualTo("2027-08-29");
            assertThat(copy.get("handledBy").isNull()).isTrue();
        }
        assertThat(actions(signedInAs(SMO_A))).as("not the SMO/Tech Lead's step").isEmpty();

        dispatched();

        assertThat(templatesTo(CM)).containsExactly(DUE_SOON_TEMPLATE, DUE_TEMPLATE, OVERDUE_TEMPLATE, WEEKLY_TEMPLATE);
        assertThat(templatesTo(POC_ONE)).containsExactly(COPY_TEMPLATE);
        assertThat(templatesTo(POC_TWO)).containsExactly(COPY_TEMPLATE);
    }

    @Test
    void bothVaPocsAreRemindedTwoWeeksAndOneWeekBeforeTheQuarterEndsAndEitherEndorsingClearsItForBoth() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();

        plannedOn("2027-10-16");
        plannedOn("2027-10-17");
        plannedOn("2027-10-24");

        ApiClient poc = vaPoc();
        assertThat(actions(poc)).containsExactly("ENDORSE_DUE_IN_ONE_WEEK", "ENDORSE_DUE_IN_TWO_WEEKS", "ENDORSE");
        JsonNode twoWeeks = only(poc, "ENDORSE_DUE_IN_TWO_WEEKS");
        assertThat(twoWeeks.get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(twoWeeks.get("dueOn").asString()).isEqualTo("2027-10-31");
        assertThat(twoWeeks.get("link").asString()).isEqualTo("/va-poc/submissions/" + sub);
        assertThat(twoWeeks.get("message").asString())
                .isEqualTo("Endorsement is due in 2 weeks: endorse the VA Submission, or return it for rework.");
        assertThat(only(poc, "ENDORSE_DUE_IN_ONE_WEEK").get("message").asString()).as("counted from endorse_due")
                .isEqualTo("Endorsement is due in 1 week: endorse the VA Submission, or return it for rework.");
        assertThat(actions(signedInAs(CM))).as("the Cyber Manager's step is done").containsExactly("REVIEW");
        assertThat(actions(signedInAs(SMO_A))).as("nor is it the SMO/Tech Lead's").isEmpty();

        endorsed(otherVaPoc(), sub);

        for (String action : List.of("ENDORSE_DUE_IN_TWO_WEEKS", "ENDORSE_DUE_IN_ONE_WEEK")) {
            assertThat(only(vaPoc(), action).get("handledBy").asString()).isEqualTo("POC Two");
        }
        dispatched();
        assertThat(templatesTo(POC_ONE)).containsExactly(DUE_SOON_TEMPLATE, DUE_SOON_TEMPLATE);
        assertThat(templatesTo(POC_TWO)).containsExactly(DUE_SOON_TEMPLATE, DUE_SOON_TEMPLATE);
    }

    @Test
    void aSmoReworkingAReturnIsRemindedAgainstTheReviewWindowItsStateWaitsOn() {
        Draft draft = returnedForRework();

        plannedOn("2027-08-24");

        JsonNode item = only(signedInAs(SMO_A), "SUBMIT_DUE_SOON");
        assertThat(item.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(item.get("dueOn").asString()).isEqualTo("2027-08-29");
        assertThat(item.get("link").asString()).isEqualTo("/smo/submissions/" + draft.submissionId() + "/rework");
        assertThat(actions(signedInAs(CM))).as("not the Cyber Manager's step any more")
                .doesNotContain("REVIEW_DUE_SOON");
    }

    @Test
    void aFinalOrFrozenVaSubmissionIsNeverReminded() {
        Draft draft = draft();
        String sub = draft.submissionId();
        today("2027-08-16");
        arrange.overdueSweep();

        String[][] cases = {{"EXEMPTION_REQUESTED", "2027-08-10"}, {"EXEMPTED", "2027-08-15"},
            {"ENDORSED", "2027-08-23"}, {"NOT_ENDORSED", "2027-08-30"}, {"WITHDRAWN", "2027-09-06"}};
        for (String[] stateOn : cases) {
            arrange.submissionState(sub, stateOn[0]);
            plannedOn(stateOn[1]);
        }

        assertThat(actions(signedInAs(SMO_A))).as("only the sweep's Overdue message").containsExactly("SUBMIT_OVERDUE");
        assertThat(actions(signedInAs(CM))).as("no copy either").isEmpty();
        assertThat(actions(signedInAs(SMO_B))).as("the Not Started System beside it is reminded")
                .contains("SUBMIT_DUE_SOON", "SUBMIT_DUE_TODAY", "SUBMIT_STILL_OVERDUE");
    }

    @Test
    void aVaSubmissionWithdrawnByTheRegisterAndItsRetiredSystemAreNeverReminded() {
        Draft draft = draft();
        ApiClient poc = vaPoc();
        // SMO_A keeps a System of Q4, so they still hold a role and can read the in-app list (ADR 0043 item 7).
        applyConfirming(poc, preview(poc, file(List.of(F2, listed("SYS-F3", "File three", SMO_A, CM, "Q4")))),
                List.of(draft.submissionId()));

        for (String day : List.of("2027-08-10", "2027-08-15", "2027-08-16", "2027-08-23")) {
            plannedOn(day);
            arrange.overdueSweep();
        }

        assertThat(actions(signedInAs(SMO_A))).containsExactly("WITHDRAWN");
        assertThat(actions(signedInAs(CM))).as("no copy for the retired System").isEmpty();
        assertThat(actions(signedInAs(SMO_B))).containsExactly("SUBMIT_STILL_OVERDUE", "SUBMIT_OVERDUE",
                "SUBMIT_DUE_TODAY", "SUBMIT_DUE_SOON");
    }

    @Test
    void aQuarterHandledOutsideTheAppIsNeverReminded() {
        // Q2 of VA Cycle 2027 starts on Monday 3 May; its Submission Window would end on Sunday 16 May.
        today("2027-05-11");
        registered(listed("SYS-Q2", "Second quarter", SMO_A, CM, "Q2"),
                listed("SYS-Q3", "Third quarter", SMO_B, CM, "Q3"));

        arrange.reminders();
        plannedOn("2027-05-16");

        assertThat(actions(signedInAs(SMO_A))).isEmpty();
        assertThat(actions(signedInAs(CM))).isEmpty();
        assertThat(actions(vaPoc())).isEmpty();
        assertThat(arrange.mailOutbox()).isEmpty();
    }

    @Test
    void aSystemAddedAfterItsSubmissionWindowIsOverdueAtOnceAndRemindedEveryWeekAfter() {
        // Reviewer decisions, 2026-10-23: until Release 2 gives an added System its own windows, its SMO/Tech Lead is
        // told at once, and va_system.added_on does not hold it back. The weekly reminder counts from that message.
        today("2027-08-20");
        registered(F2);
        arrange.overdueSweep();

        plannedOn("2027-08-23");
        assertThat(actions(signedInAs(SMO_B))).as("not 3 days after the Overdue message").containsExactly(
                "SUBMIT_OVERDUE");
        plannedOn("2027-08-27");

        JsonNode weekly = only(signedInAs(SMO_B), "SUBMIT_STILL_OVERDUE");
        assertThat(weekly.get("dueOn").asString()).as("still due by its Quarter's window").isEqualTo("2027-08-15");
        assertThat(actions(signedInAs(CM_OTHER))).containsExactly("SUBMIT_OVERDUE_COPY");
    }

    @Test
    void aReviewThatKeptTheFlagOfItsDraftIsRemindedWeeklyFromTheDayTheFlagWasSet() {
        Draft draft = draft();
        today("2027-08-16");
        arrange.overdueSweep();
        // Submitted after the Review Window too: the flag stays (ADR 0033 item 4), so the Cyber Manager's weekly
        // reminders keep the week of the day it was set.
        arrange.submissionState(draft.submissionId(), "IN_REVIEW");

        plannedOn("2027-08-30");

        assertThat(actions(signedInAs(CM))).containsExactly("REVIEW_STILL_OVERDUE");
        assertThat(actions(signedInAs(SMO_A))).as("not the SMO/Tech Lead's step any more").containsExactly(
                "SUBMIT_OVERDUE");
    }

    @Test
    void aPassThatCouldNotWriteIsMadeGoodByARerunOfTheSameDay() {
        draft();
        today("2027-08-10");

        arrange.notificationFault("on");
        try {
            arrange.reminders();
        } finally {
            arrange.notificationFault("off");
        }
        assertThat(arrange.mailOutbox()).as("nothing was written").isEmpty();
        arrange.reminders();
        arrange.reminders();

        assertThat(actions(signedInAs(SMO_A))).containsExactly("SUBMIT_DUE_SOON");
        assertThat(actions(signedInAs(SMO_B))).containsExactly("SUBMIT_DUE_SOON");
        assertThat(outboxOf(SMO_A)).hasSize(1);
        assertThat(onlyMailOf(SMO_B).get("template").asString()).isEqualTo("REMINDER_DUE_SOON");
    }
}
