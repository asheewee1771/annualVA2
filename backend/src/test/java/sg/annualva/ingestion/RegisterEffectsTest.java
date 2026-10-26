package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.CapturingMailRelay.Sent;
import sg.annualva.harness.Samples;
import sg.annualva.submission.SubmissionEvent;
import sg.annualva.submission.SubmissionState;
import tools.jackson.databind.JsonNode;

/**
 * Stories 5, 6 and 7 (ticket 43): a Register preview names what each row would do to in-flight work (a VA Submission
 * that is not in a final state), and the apply withdraws a retired System's in-flight VA Submission only once the VA POC
 * has confirmed it. SYS-F1 (SMO_A, CM, Q3) has a Draft; SYS-F2 (SMO_B, CM_OTHER, Q3) is Not Started. Q3 of VA Cycle 2027
 * is the first live Quarter: its Submission Window ends on 15 August 2027.
 */
class RegisterEffectsTest extends MailSeam1 {

    private static final Listed F1 = listed("SYS-F1", "File one", SMO_A, CM, "Q3");
    private static final Listed F2 = listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3");

    @Test
    void retiringASystemWithAnInFlightVaSubmissionIsShownUnderWillWithdraw() {
        Draft draft = draft();

        JsonNode preview = preview(vaPoc(), file(List.of(F2)));

        JsonNode row = rowOf(preview, "SYS-F1");
        assertThat(row.get("outcome").asString()).isEqualTo("RETIRE");
        assertThat(texts(row.get("effects"))).containsExactly("WILL_WITHDRAW");
        assertThat(row.get("inFlight")).hasSize(1);
        JsonNode work = row.get("inFlight").get(0);
        assertThat(work.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(work.get("state").asString()).isEqualTo("DRAFT");
        assertThat(work.get("cycle").asInt()).isEqualTo(2027);
        assertThat(preview.get("counts").get("willWithdraw").asInt()).isEqualTo(1);
        assertThat(preview.get("canApply").asBoolean()).as("a withdrawal is confirmed, not blocked").isTrue();
        assertThat(texts(rowOf(preview, "SYS-F2").get("effects"))).as("no work in flight, no effect").isEmpty();
    }

    @Test
    void anApplyThatDoesNotConfirmEachWithdrawalIsRefusedAndChangesNothing() {
        Draft draft = draft();
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, file(List.of(F2)));

        var unconfirmed = applyResponse(poc, preview);
        var another = applyConfirming(poc, preview, List.of(draft.systemId()));

        for (var response : List.of(unconfirmed, another)) {
            assertThat(response.status()).as(response.body()).isEqualTo(400);
            assertThat(problemType(response)).endsWith("withdrawal-unconfirmed");
        }
        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");
        assertThat(retiredFlag(poc, "SYS-F1")).as("nothing was applied").isFalse();
        assertThat(applyConfirming(poc, preview, List.of(draft.submissionId())).status())
                .as("the preview can still be applied once confirmed").isEqualTo(200);
    }

    @Test
    void aConfirmedWithdrawalMakesTheVaSubmissionWithdrawnReadOnlyAuditedAndNeverOverdue() {
        Draft draft = draft();
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, file(List.of(F2)));
        events.reset();

        var response = applyConfirming(poc, preview, List.of(draft.submissionId()));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(ApiClient.json(response).get("withdrawn").asInt()).isEqualTo(1);
        assertThat(ApiClient.json(response).get("retired").asInt()).isEqualTo(1);
        JsonNode withdrawn = submission(poc, draft.submissionId());
        assertThat(withdrawn.get("state").asString()).isEqualTo("WITHDRAWN");
        JsonNode entry = entries("SUBMISSION_WITHDRAWN", draft.systemId()).get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(entry.get("detail").get("state_from").asString()).isEqualTo("DRAFT");
        assertThat(entry.get("detail").get("state_to").asString()).isEqualTo("WITHDRAWN");
        assertThat(entry.get("detail").get("preview_id").asString()).isEqualTo(preview.get("id").asString());
        assertThat(events.published()).extracting(SubmissionEvent::submissionId, SubmissionEvent::from, SubmissionEvent::to)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(java.util.UUID.fromString(draft.submissionId()),
                        SubmissionState.DRAFT, SubmissionState.WITHDRAWN));

        assertThat(read(signedInAs(SMO_A), draft.submissionId()).status())
                .as("the former SMO/Tech Lead holds no role any more").isEqualTo(403);
        today("2027-08-20");
        arrange.overdueSweep();
        assertThat(submission(otherVaPoc(), draft.submissionId()).get("overdue").asBoolean())
                .as("a final state waits on no window").isFalse();
    }

    @Test
    void theSmoIsToldOfTheWithdrawalInTheAppAndByMailAsARegisterChange() {
        Draft draft = draft();
        ApiClient poc = vaPoc();
        // SMO_A keeps another System, so they still hold a role and can read the in-app list (ADR 0043 item 7).
        applyConfirming(poc, preview(poc, file(List.of(F2, listed("SYS-F3", "File three", SMO_A, CM, "Q4")))),
                List.of(draft.submissionId()));

        JsonNode item = only(signedInAs(SMO_A), "WITHDRAWN");
        assertThat(item.get("systemCode").asString()).isEqualTo("SYS-F1");
        assertThat(item.get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(item.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(item.get("dueOn").isNull()).as("nothing is due").isTrue();
        assertThat(item.get("link").asString()).as("the System is no longer theirs to open").isEqualTo("/smo");

        dispatched();

        assertThat(mailTo(SMO_A)).hasSize(1);
        Sent mail = mailTo(SMO_A).getFirst();
        assertThat(mail.templateId()).isEqualTo(REGISTER_CHANGE_TEMPLATE);
        assertThat(mail.values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "system_id", "SYS-F1",
                "system_name", "File one",
                "state", "Withdrawn",
                "action", "The System Register retired this System, so its VA Submission is Withdrawn: nothing more is due.",
                "link", APP_URL + "/smo",
                "change_kind", "Withdrawn"));
        assertThat(mailTo(CM)).as("the Cyber Manager is not told").isEmpty();
    }

    @Test
    void withdrawingAVaSubmissionAwaitingEndorsementClearsTheVaPocPairForBoth() {
        Draft draft = awaitingEndorsement();
        ApiClient poc = vaPoc();

        applyConfirming(poc, preview(poc, file(List.of(F2))), List.of(draft.submissionId()));

        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("WITHDRAWN");
        for (ApiClient reader : List.of(vaPoc(), otherVaPoc())) {
            JsonNode pair = only(reader, "ENDORSE");
            assertThat(pair.get("handledBy").asString()).as("handled by the VA POC who applied").isEqualTo("POC One");
        }
    }

    @Test
    void aVaSubmissionWithdrawnWhileItsExemptionIsRequestedIsToldAsAWithdrawalNotADecision() {
        Draft draft = draft();
        arrange.submissionState(draft.submissionId(), "EXEMPTION_REQUESTED");
        ApiClient poc = vaPoc();
        // SMO_A keeps another System, so they still hold a role and can read the in-app list (ADR 0043 item 7).
        applyConfirming(poc, preview(poc, file(List.of(F2, listed("SYS-F3", "File three", SMO_A, CM, "Q4")))),
                List.of(draft.submissionId()));

        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(actions(signedInAs(SMO_A))).containsExactly("WITHDRAWN");
    }

    @Test
    void aNotStartedRowARejectedExemptionLeftIsNotInFlightWork() {
        draft();
        String f2 = idOf("SYS-F2");
        String notStarted = started(signedInAs(SMO_B), f2).get("id").asString();
        arrange.submissionState(notStarted, "NOT_STARTED");

        JsonNode row = rowOf(preview(vaPoc(), file(List.of(F1))), "SYS-F2");

        assertThat(row.get("outcome").asString()).isEqualTo("RETIRE");
        assertThat(texts(row.get("effects"))).isEmpty();
    }

    @Test
    void aRetirementWhoseVaSubmissionHasAFileStillScanningIsBlockedUntilTheScanSettles() {
        Draft draft = draft();
        uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, "results.xml", Samples.xccdfResults());
        ApiClient poc = vaPoc();

        JsonNode scanning = preview(poc, file(List.of(F2)));

        JsonNode row = rowOf(scanning, "SYS-F1");
        assertThat(row.get("outcome").asString()).as("still a retirement, held back").isEqualTo("RETIRE");
        assertThat(texts(row.get("effects"))).containsExactly("WILL_WITHDRAW");
        assertThat(texts(row.get("reasons"))).containsExactly("FILES_SCANNING");
        assertThat(scanning.get("counts").get("heldBack").asInt()).isEqualTo(1);
        assertThat(scanning.get("counts").get("blocked").asInt()).as("nothing is wrong in the file").isZero();
        assertThat(scanning.get("canApply").asBoolean()).isFalse();
        var refused = applyConfirming(poc, scanning, List.of(draft.submissionId()));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(problemType(refused)).endsWith("preview-blocked");

        pass();
        pass();
        JsonNode settled = preview(poc, file(List.of(F2)));
        assertThat(texts(rowOf(settled, "SYS-F1").get("reasons"))).isEmpty();
        assertThat(applyConfirming(poc, settled, List.of(draft.submissionId())).status()).isEqualTo(200);
    }

    @Test
    void aRescanStillBeingCheckedHoldsTheRetirementBackToo() {
        Draft draft = draft();
        String first = settled(draft, SECURE_CONFIGURATION, "results.xml", Samples.xccdfResults()).get("id").asString();
        replaced(draft, first, "rescan.xml", Samples.xccdfResults());

        JsonNode row = rowOf(preview(vaPoc(), file(List.of(F2))), "SYS-F1");

        assertThat(texts(row.get("reasons"))).containsExactly("FILES_SCANNING");
    }

    @Test
    void aFileUploadedAfterThePreviewIsCaughtByTheApply() {
        Draft draft = draft();
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, file(List.of(F2)));
        uploaded(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, "results.xml", Samples.xccdfResults());

        var response = applyConfirming(poc, preview, List.of(draft.submissionId()));

        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(problemType(response)).endsWith("work-changed");
        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");
    }

    @Test
    void aVaSubmissionStartedAfterThePreviewIsCaughtByTheApply() {
        draft();
        String f2 = idOf("SYS-F2");
        ApiClient poc = vaPoc();
        JsonNode preview = preview(poc, file(List.of(F1)));
        assertThat(texts(rowOf(preview, "SYS-F2").get("effects"))).as("Not Started when previewed").isEmpty();
        started(signedInAs(SMO_B), f2);

        var response = applyResponse(poc, preview);

        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(problemType(response)).endsWith("work-changed");
        assertThat(retiredFlag(poc, "SYS-F2")).isFalse();
    }

    @Test
    void aMoveOrATransferOfASystemWithInFlightWorkIsShownAndBlockedInRelease1() {
        draft();
        ApiClient poc = vaPoc();

        JsonNode preview = preview(poc, file(List.of(
                listed("SYS-F1", "File one", SMO_A, CM, "Q4"),
                listed("SYS-F2", "File two", SMO_A, CM, "Q4"))));
        JsonNode moved = rowOf(preview, "SYS-F1");
        assertThat(moved.get("outcome").asString()).isEqualTo("UPDATE");
        assertThat(texts(moved.get("effects"))).containsExactly("WILL_MOVE");
        assertThat(texts(moved.get("reasons"))).containsExactly("MOVE_IN_FLIGHT");
        assertThat(moved.get("before").get("quarter").asString()).isEqualTo("Q3");
        assertThat(moved.get("after").get("quarter").asString()).isEqualTo("Q4");
        assertThat(texts(rowOf(preview, "SYS-F2").get("reasons"))).as("Not Started: an ordinary update").isEmpty();
        assertThat(preview.get("canApply").asBoolean()).isFalse();

        JsonNode transfers = preview(poc, file(List.of(
                listed("SYS-F1", "File one", SMO_B, CM, "Q3"), F2)));
        JsonNode transferred = rowOf(transfers, "SYS-F1");
        assertThat(transferred.get("outcome").asString()).isEqualTo("UPDATE");
        assertThat(texts(transferred.get("effects"))).containsExactly("WILL_TRANSFER");
        assertThat(texts(transferred.get("reasons"))).containsExactly("TRANSFER_IN_FLIGHT");
        assertThat(transfers.get("counts").get("willTransfer").asInt()).isEqualTo(1);
        assertThat(transfers.get("canApply").asBoolean()).isFalse();

        JsonNode reviewer = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_A, CM_OTHER, "Q3"), F2))),
                "SYS-F1");
        assertThat(texts(reviewer.get("effects"))).as("a Cyber Manager change is a transfer").containsExactly("WILL_TRANSFER");

        JsonNode renamed = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one renamed", SMO_A, CM, "Q3"), F2))),
                "SYS-F1");
        assertThat(renamed.get("outcome").asString()).as("a rename touches no work").isEqualTo("UPDATE");
        assertThat(texts(renamed.get("effects"))).isEmpty();
    }

    @Test
    void aChangeThatOnlyReplacesASuspendedPersonIsAppliedDespiteInFlightWork() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient poc = vaPoc();
        assertThat(poc.post("/api/role-holders/" + grantOf(poc, "SMO", SMO_A, "SYS-F1") + "/suspend").status())
                .isEqualTo(200);

        JsonNode preview = preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_B, CM, "Q3"), F2)));
        JsonNode replaced = rowOf(preview, "SYS-F1");
        assertThat(texts(replaced.get("effects"))).containsExactly("WILL_TRANSFER");
        assertThat(texts(replaced.get("reasons"))).as("the one exception to the hold-back (ADR 0049)").isEmpty();
        assertThat(preview.get("counts").get("heldBack").asInt()).isZero();
        assertThat(preview.get("canApply").asBoolean()).isTrue();
        JsonNode both = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_B, CM_OTHER, "Q3"), F2))),
                "SYS-F1");
        assertThat(texts(both.get("reasons"))).as("the Cyber Manager replaced is not suspended")
                .containsExactly("TRANSFER_IN_FLIGHT");

        apply(poc, preview);

        assertThat(submission(signedInAs(SMO_B), sub).get("state").asString()).as("the new SMO/Tech Lead has it")
                .isEqualTo("IN_REVIEW");
        assertThat(read(signedInAs(SMO_A), sub).status()).as("the suspended grant ended, and it was their only role")
                .isEqualTo(403);
        assertThat(only(signedInAs(SMO_B), "SYSTEM_TRANSFERRED_TO_YOU").get("systemCode").asString())
                .isEqualTo("SYS-F1");
    }

    /** The grant this person holds for this role on this System, as the role-holder review lists it. */
    private static String grantOf(ApiClient poc, String role, String email, String systemCode) {
        for (JsonNode holder : ApiClient.json(poc.get("/api/role-holders")).get("holders")) {
            if (holder.get("role").asString().equals(role) && holder.get("email").asString().equalsIgnoreCase(email)
                    && systemCode.equals(holder.get("systemCode").asString(""))) {
                return holder.get("id").asString();
            }
        }
        throw new AssertionError("no " + role + " grant for " + systemCode);
    }

    @Test
    void aChangeThatGivesOnePersonTwoStepsOfAnInFlightVaSubmissionIsBlockedForSegregationOfDuties() {
        inReview();
        ApiClient poc = vaPoc();

        JsonNode swapped = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", CM, SMO_B, "Q3"), F2))),
                "SYS-F1");
        JsonNode submitterReviews = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_B, SMO_A, "Q3"),
                F2))), "SYS-F1");
        JsonNode independent = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_B, CM, "Q3"), F2))),
                "SYS-F1");

        assertThat(texts(swapped.get("reasons"))).as("its Cyber Manager would become its SMO/Tech Lead")
                .containsExactly("TRANSFER_IN_FLIGHT", "SEGREGATION_OF_DUTIES");
        assertThat(texts(submitterReviews.get("reasons"))).as("who submitted it would review it")
                .containsExactly("TRANSFER_IN_FLIGHT", "SEGREGATION_OF_DUTIES");
        assertThat(texts(independent.get("reasons"))).containsExactly("TRANSFER_IN_FLIGHT");
    }

    @Test
    void aVaPocWhoReturnedTheVaSubmissionMayNotBecomeItsSmoOrCyberManager() {
        Draft draft = awaitingEndorsement();
        ApiClient poc = vaPoc();
        returned(poc, draft.submissionId(), "Show me the evidence.");

        JsonNode asSmo = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", POC_ONE, CM, "Q3"), F2))),
                "SYS-F1");
        JsonNode asManager = rowOf(preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_A, POC_ONE, "Q3"), F2))),
                "SYS-F1");

        assertThat(texts(asSmo.get("reasons"))).as("they acted at the endorsing step")
                .containsExactly("TRANSFER_IN_FLIGHT", "SEGREGATION_OF_DUTIES");
        assertThat(texts(asManager.get("reasons"))).containsExactly("TRANSFER_IN_FLIGHT", "SEGREGATION_OF_DUTIES");
    }

    @Test
    void aNotStartedSystemMovedToAQuarterWhoseWindowIsOpenIsNoLongerOverdue() {
        registered(F1, F2);
        today("2027-08-17");
        arrange.overdueSweep();
        assertThat(inboxItem(signedInAs(SMO_B), "SYS-F2").get("overdue").asBoolean()).as("Q3's window passed").isTrue();

        registered(F1, listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q4"));
        arrange.overdueSweep();

        assertThat(inboxItem(signedInAs(SMO_B), "SYS-F2").get("overdue").asBoolean())
                .as("the flag follows Q4's window, which is still open").isFalse();
        assertThat(inboxItem(signedInAs(SMO_A), "SYS-F1").get("overdue").asBoolean()).as("not moved").isTrue();
    }

    @Test
    void aWithdrawnVaSubmissionLeavesTheBoardsAndTheirCounts() {
        Draft draft = draft();
        ApiClient poc = vaPoc();
        applyConfirming(poc, preview(poc, file(List.of(F2))), List.of(draft.submissionId()));

        JsonNode board = ApiClient.json(vaPoc().get("/api/dashboards/quarter-board?cycle=2027&quarter=3"));
        List<String> listedAnywhere = new ArrayList<>();
        board.get("columns").forEach(column -> column.get("systems")
                .forEach(card -> listedAnywhere.add(card.get("systemId").asString())));
        assertThat(listedAnywhere).containsExactly("SYS-F2");
    }

    private boolean retiredFlag(ApiClient poc, String systemId) {
        for (JsonNode system : ApiClient.json(poc.get("/api/systems"))) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system.get("retired").asBoolean();
            }
        }
        throw new AssertionError("no System " + systemId);
    }

    private static JsonNode inboxItem(ApiClient smo, String systemId) {
        var response = smo.get("/api/dashboards/smo-inbox");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode system : ApiClient.json(response).get("systems")) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no System " + systemId + " in " + response.body());
    }

    static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asString()));
        return texts;
    }
}
