package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 44 and 46 (ticket 40): a VA POC approves an Exemption, after a fresh sign-in, and the VA Submission is Exempted
 * with its work kept, or rejects it and the VA Submission is back exactly where it was. Both decisions are audited,
 * tell the SMO/Tech Lead and clear the other VA POC's item; a VA POC segregation of duties refuses leaves it to the other.
 */
class ExemptionDecisionTest extends ExemptionSeam1 {

    @Test
    void anApprovalExemptsTheVaSubmissionAndKeepsItsWork() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        List<String> findings = findingIds(draft.smo(), sub);
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();
        events.reset();
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(1));

        JsonNode answered = approved(poc, id);

        assertThat(answered.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(answered.get("decidedBy").asString()).isEqualTo(userId(poc));
        assertThat(answered.get("decidedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(answered.get("conditional").asBoolean()).as("a prior VA").isFalse();
        assertThat(answered.get("lapsesOn").isNull()).isTrue();
        JsonNode exempted = submission(draft.smo(), sub);
        assertThat(exempted.get("state").asString()).isEqualTo(EXEMPTED);
        assertThat(actedAt(exempted, "ENDORSE")).containsExactly(userId(poc));
        assertThat(findingIds(draft.smo(), sub)).as("the work is kept for audit").containsExactlyElementsOf(findings);
        assertThat(inboxRow(draft.smo(), "SYS-F1").get("nextAction").isNull()).as("Exempted is final").isTrue();

        SubmissionEvent event = events.published().getFirst();
        assertThat(event.from().name()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(event.to().name()).isEqualTo(EXEMPTED);
        JsonNode audited = entries("EXEMPTION_APPROVED", draft.systemId()).get(0);
        assertThat(audited.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get("detail").get("state_from").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(audited.get("detail").get("state_to").asString()).isEqualTo(EXEMPTED);
        assertThat(audited.get("detail").get("exemption_id").asString()).isEqualTo(id);

        JsonNode told = only(draft.smo(), "EXEMPTION_APPROVED");
        assertThat(told.get("state").asString()).isEqualTo(EXEMPTED);
        assertThat(told.get("link").asString()).isEqualTo("/smo/submissions/" + sub);
        assertThat(only(otherVaPoc(), "DECIDE_EXEMPTION").get("handledBy").asString()).isEqualTo("POC One");
        assertThat(queue(otherVaPoc())).isEmpty();
    }

    @Test
    void anApprovedScheduledVaIsAConditionalExemptionThatLapsesThreeMonthsAfterTheQuarter() {
        Draft draft = draft();
        String id = requested(draft.smo(), draft.systemId(), scheduledVa()).get("id").asString();

        JsonNode answered = approved(vaPoc(), id);

        assertThat(answered.get("conditional").asBoolean()).isTrue();
        assertThat(answered.get("lapsesOn").asString()).as("31 October 2027 plus 3 months").isEqualTo("2028-01-31");
    }

    @Test
    void aRejectionPutsADraftBackExactlyAsItWasAndTheSmoCarriesOn() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String file = settled(draft, DEPENDENCY_CHECK, "dc.json", dcReportAt(SCANNED)).get("id").asString();
        JsonNode before = submission(draft.smo(), sub);
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();
        ApiClient poc = vaPoc();

        JsonNode answered = rejected(poc, id);

        assertThat(answered.get("decision").asString()).isEqualTo("REJECTED");
        assertThat(answered.get("lapsesOn").isNull()).isTrue();
        JsonNode after = submission(draft.smo(), sub);
        assertThat(after.get("state").asString()).isEqualTo("DRAFT");
        assertThat(after.get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(withoutActed(after)).as("everything but who acted is as it was").isEqualTo(withoutActed(before));
        assertThat(status(draft.smo(), sub, file).get("status").asString()).isEqualTo("PARSED");
        assertThat(upload(draft.smo(), sub, BURP_SUITE, MACHINE_READABLE, "burp.xml", new byte[] {1}).status())
                .as("the Draft takes files again").isEqualTo(202);

        JsonNode audited = entries("EXEMPTION_REJECTED", draft.systemId()).get(0);
        assertThat(audited.get("detail").get("state_from").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(audited.get("detail").get("state_to").asString()).isEqualTo("DRAFT");
        JsonNode told = only(draft.smo(), "EXEMPTION_REJECTED");
        assertThat(told.get("state").asString()).isEqualTo("DRAFT");
        assertThat(told.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(told.get("link").asString()).isEqualTo("/smo/submissions/" + sub);
        assertThat(only(otherVaPoc(), "DECIDE_EXEMPTION").get("handledBy").asString()).isEqualTo("POC One");
    }

    @Test
    void whileFrozenFromInReviewTheCyberManagerCannotReviewAndARejectionKeepsWhatWasConfirmed() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), CONFIRMED, null);
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();

        assertConflict(reviewFinding(cm, ids.get(1), review(CONFIRMED, null, currentProposalId(cm, sub, ids.get(1)))),
                "invalid-transition");
        assertConflict(returnForRework(cm, sub, "Look again."), "invalid-transition");
        rejected(vaPoc(), id);

        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(findingById(cm, sub, ids.get(0)).get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(findingById(cm, sub, ids.get(1)).get("reviewState").asString()).isEqualTo("PENDING");
    }

    @Test
    void aRejectionPutsBackTheOverdueFlagItFroze() {
        Draft draft = draft();
        String sub = draft.submissionId();
        today("2027-08-16");
        arrange.overdueSweep();
        ApiClient smo = signedInAs(SMO_A);
        JsonNode overdue = submission(smo, sub);
        assertThat(overdue.get("overdue").asBoolean()).isTrue();
        String id = requested(smo, draft.systemId(), priorVa()).get("id").asString();
        assertThat(submission(smo, sub).get("overdue").asBoolean()).as("frozen waits on no window").isFalse();
        arrange.overdueSweep();

        rejected(vaPoc(), id);

        JsonNode after = submission(smo, sub);
        assertThat(after.get("overdue").asBoolean()).isTrue();
        assertThat(after.get("overdueSince").asString()).isEqualTo(overdue.get("overdueSince").asString());
    }

    @Test
    void aRejectionPutsAVaSubmissionAwaitingEndorsementBackAndItsEndorsementPairStaysOpen() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();
        assertThat(openActions(otherVaPoc())).containsExactlyInAnyOrder("DECIDE_EXEMPTION", "ENDORSE");

        rejected(vaPoc(), id);

        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(openActions(otherVaPoc())).as("the endorsement still waits").containsExactly("ENDORSE");
        assertThat(endorsed(otherVaPoc(), sub).get("state").asString()).isEqualTo(ENDORSED);
    }

    @Test
    void anApprovalOfAVaSubmissionAwaitingEndorsementClearsItsEndorsementPair() {
        Draft draft = awaitingEndorsement();
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();

        approved(vaPoc(), id);

        assertThat(openActions(otherVaPoc())).isEmpty();
    }

    @Test
    void aCycleClosedByAVaPocMakesAPendingRequestNotEndorsedAndClearsBothPairs() {
        Draft draft = awaitingEndorsement();
        requested(draft.smo(), draft.systemId(), priorVa());
        today("2028-02-14");
        ApiClient poc = vaPoc();

        var closed = poc.postJson("/api/cycles/2027/close", "{}");

        assertThat(closed.status()).as(closed.body()).isEqualTo(200);
        assertThat(submission(poc, draft.submissionId()).get("state").asString()).isEqualTo("NOT_ENDORSED");
        assertThat(openActions(otherVaPoc())).as("the decision and the endorsement are moot").isEmpty();
    }

    @Test
    void aRejectionPutsAVaSubmissionTheRequestCreatedBackToNotStartedAndTheSmoStartsIt() {
        draft();
        ApiClient smoB = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        JsonNode requested = requested(smoB, system, priorVa());
        String sub = requested.get("submissionId").asString();

        rejected(vaPoc(), requested.get("id").asString());

        JsonNode row = inboxRow(smoB, "SYS-F2");
        assertThat(row.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(row.get("nextAction").asString()).isEqualTo("START_SUBMISSION");
        assertThat(row.get("nextActionDueOn").asString()).isEqualTo("2027-08-15");
        assertThat(row.get("submissionId").isNull()).as("a Not Started row offers nothing to open").isTrue();
        JsonNode told = only(smoB, "EXEMPTION_REJECTED");
        assertThat(told.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(told.get("link").asString()).isEqualTo("/smo");

        JsonNode started = started(smoB, system);
        assertThat(started.get("id").asString()).as("the start takes the row the request made").isEqualTo(sub);
        assertThat(started.get("state").asString()).isEqualTo("DRAFT");
        assertThat(inboxRow(smoB, "SYS-F2").get("nextAction").asString()).isEqualTo("PREPARE_SUBMISSION");
    }

    @Test
    void aRefusedStartThatWouldTakeTheNotStartedVaSubmissionNamesItAndLeavesItNotStarted() {
        draft();
        ApiClient smoB = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        JsonNode requested = requested(smoB, system, priorVa());
        String sub = requested.get("submissionId").asString();
        rejected(vaPoc(), requested.get("id").asString());
        arrange.grant("CYBER_MANAGER", SMO_B, system);

        assertSodConflict(start(signedInAs(SMO_B), system));

        JsonNode refused = entries("SUBMISSION_STARTED", system).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("submissionId").asString()).as("an existing row the start would have taken")
                .isEqualTo(sub);
        assertThat(inboxRow(signedInAs(SMO_B), "SYS-F2").get("state").asString()).as("the take was rolled back")
                .isEqualTo("NOT_STARTED");
    }

    @Test
    void aMoveOfASystemWhoseVaSubmissionARejectionLeftNotStartedLinksTheSmoToTheirInbox() {
        draft();
        ApiClient smoB = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        JsonNode requested = requested(smoB, system, priorVa());
        rejected(vaPoc(), requested.get("id").asString());

        registered(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q4"));

        JsonNode moved = only(smoB, "SYSTEM_MOVED");
        assertThat(moved.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(moved.get("link").asString()).as("a Not Started VA Submission is started from the inbox")
                .isEqualTo("/smo");
    }

    @Test
    void decidingNeedsAFreshSignInAndChangesNothingWithoutOne() {
        Draft draft = draft();
        String id = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();
        ApiClient poc = vaPoc();
        clock.advance(Duration.ofMinutes(6));

        for (var refused : List.of(approve(poc, id), reject(poc, id))) {
            assertThat(refused.status()).isEqualTo(403);
            assertThat(ApiClient.json(refused).get("stepUpPath").asString())
                    .isEqualTo("/auth/step-up?intent=" + DECIDE_INTENT);
        }
        assertThat(submission(draft.smo(), draft.submissionId()).get("state").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(entries("EXEMPTION_APPROVED", draft.systemId())).isEmpty();

        SignIn.stepUp(poc, issuer, "/auth/step-up?intent=" + DECIDE_INTENT + "&returnTo=/va-poc/exemptions",
                fresh(TestIdentity.of("af-5001", POC_ONE, "POC One")));
        poc.withCsrf();
        assertThat(approved(poc, id).get("decision").asString()).isEqualTo("APPROVED");
    }

    @Test
    void aVaPocTheRegisterNamesAsTheSystemsSmoOrCyberManagerLeavesTheDecisionToTheOtherVaPoc() {
        registered(List.of(listed("SYS-F1", "File one", POC_ONE, CM, "Q3"), listed("SYS-F2", "File two", SMO_B,
                POC_ONE, "Q3")), List.of());
        String f1 = idOf("SYS-F1");
        String f2 = idOf("SYS-F2");
        String asSmo = requested(vaPoc(), f1, priorVa()).get("id").asString();
        String asManager = requested(signedInAs(SMO_B), f2, priorVa()).get("id").asString();
        assertThat(queue(vaPoc())).extracting(request -> request.get("sodConflict").asBoolean())
                .containsExactly(true, true);

        assertSodConflict(approve(vaPoc(), asSmo));
        assertSodConflict(reject(vaPoc(), asManager));

        JsonNode refused = entries("EXEMPTION_APPROVED", f1).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        assertThat(refused.get("detail").get("exemption_id").asString()).isEqualTo(asSmo);
        assertThat(queue(otherVaPoc())).extracting(request -> request.get("sodConflict").asBoolean())
                .containsExactly(false, false);
        approved(otherVaPoc(), asSmo);
        rejected(otherVaPoc(), asManager);
    }

    @Test
    void onlyAVaPocDecidesAndOnlyOnceAndOnlyThePendingRequest() {
        Draft draft = draft();
        String first = requested(draft.smo(), draft.systemId(), priorVa()).get("id").asString();

        assertThat(approve(draft.smo(), first).status()).as("not a VA POC").isEqualTo(403);
        assertThat(reject(signedInAs(CM), first).status()).as("not a VA POC").isEqualTo(403);
        assertNotFound(approve(vaPoc(), UUID.randomUUID().toString()));
        rejected(vaPoc(), first);

        assertConflict(reject(otherVaPoc(), first), "invalid-transition");
        String second = requested(draft.smo(), draft.systemId(), scheduledVa()).get("id").asString();
        assertConflict(approve(otherVaPoc(), first), "invalid-transition");

        JsonNode reasons = entries("EXEMPTION_APPROVED", draft.systemId()).get(0).get("detail");
        assertThat(reasons.get("reason").asString()).isEqualTo("already-decided");
        assertThat(entries("EXEMPTION_REJECTED", draft.systemId()).get(0).get("detail").get("reason").asString())
                .isEqualTo("state-not-decidable");
        assertThat(approved(otherVaPoc(), second).get("decision").asString()).isEqualTo("APPROVED");
    }

    /** The VA Submission as read, without who acted at which step: a request and a decision each add one. */
    private static JsonNode withoutActed(JsonNode submission) {
        var copy = submission.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) copy).remove("acted");
        return copy;
    }
}
