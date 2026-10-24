package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 43 and 44 (ticket 40): an SMO/Tech Lead requests an Exemption for their System at any time before endorsement,
 * with evidence of a VA within the 3 months before its Quarter or one scheduled within the 3 months after, and the VA
 * Submission is frozen until a VA POC decides. A Not Started System gets its VA Submission in the same call.
 */
class ExemptionRequestTest extends ExemptionSeam1 {

    @Test
    void anSmoRequestsAnExemptionForADraftAndTheVaSubmissionFreezes() {
        Draft draft = draft();
        String sub = draft.submissionId();
        events.reset();

        JsonNode exemption = requested(draft.smo(), draft.systemId(), priorVa());

        assertThat(exemption.get("submissionId").asString()).isEqualTo(sub);
        assertThat(exemption.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(exemption.get("basis").asString()).isEqualTo(PRIOR_VA);
        assertThat(exemption.get("vaDate").asString()).isEqualTo("2027-07-15");
        assertThat(exemption.get("evidence").asString()).isEqualTo("Third-party VA by Acme, report ref AC-2027-07.");
        assertThat(exemption.get("evidenceFileId").isNull()).isTrue();
        assertThat(exemption.get("previousState").asString()).isEqualTo("DRAFT");
        assertThat(exemption.get("requestedBy").asString()).isEqualTo(userId(draft.smo()));
        assertThat(exemption.get("requestedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(exemption.get("decision").isNull()).isTrue();
        assertThat(exemption.get("conditional").asBoolean()).isFalse();
        assertThat(exemption.get("lapsesOn").isNull()).isTrue();

        JsonNode frozen = submission(draft.smo(), sub);
        assertThat(frozen.get("state").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(actedAt(frozen, "SUBMIT")).containsExactly(userId(draft.smo()));
        JsonNode row = inboxRow(draft.smo(), "SYS-F1");
        assertThat(row.get("state").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(row.get("nextAction").asString()).isEqualTo("AWAIT_EXEMPTION_DECISION");
        assertThat(row.get("nextActionDueOn").isNull()).as("a frozen VA Submission waits on no window").isTrue();

        assertThat(events.published()).hasSize(1);
        SubmissionEvent event = events.published().getFirst();
        assertThat(event.from().name()).isEqualTo("DRAFT");
        assertThat(event.to().name()).isEqualTo(EXEMPTION_REQUESTED);

        JsonNode audited = entries("EXEMPTION_REQUESTED", draft.systemId());
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        JsonNode detail = audited.get(0).get("detail");
        assertThat(detail.get("state_from").asString()).isEqualTo("DRAFT");
        assertThat(detail.get("state_to").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(detail.get("exemption_id").asString()).isEqualTo(exemption.get("id").asString());
        assertThat(detail.get("basis").asString()).isEqualTo(PRIOR_VA);
        assertThat(audited.get(0).toString()).as("the evidence a person typed is never audited").doesNotContain("Acme");
    }

    @Test
    void bothVaPocsAreAskedToDecideAndTheSmoIsToldNothing() {
        Draft draft = draft();

        requested(draft.smo(), draft.systemId(), priorVa());

        for (ApiClient poc : List.of(vaPoc(), otherVaPoc())) {
            JsonNode item = only(poc, "DECIDE_EXEMPTION");
            assertThat(item.get("state").asString()).isEqualTo(EXEMPTION_REQUESTED);
            assertThat(item.get("message").asString()).isEqualTo("Approve or reject the Exemption request.");
            assertThat(item.get("dueOn").isNull()).isTrue();
            assertThat(item.get("link").asString()).isEqualTo("/va-poc/exemptions");
            assertThat(item.toString()).as("a notification carries no evidence").doesNotContain("Acme");
        }
        assertThat(notifications(draft.smo())).isEmpty();
    }

    @Test
    void whileFrozenNothingIsEditedSubmittedOrRequestedAgain() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        requested(draft.smo(), draft.systemId(), priorVa());

        assertConflict(upload(draft.smo(), sub, BURP_SUITE, EVIDENCE, "late.pdf", Samples.pdf()), "invalid-transition");
        assertConflict(submitAccepting(draft.smo(), sub), "invalid-transition");
        assertConflict(request(draft.smo(), draft.systemId(), scheduledVa()), "invalid-transition");

        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(EXEMPTION_REQUESTED);
        JsonNode refused = entries("EXEMPTION_REQUESTED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("state-not-exemptible");
    }

    @Test
    void anExemptionCanBeRequestedInReview() {
        Draft review = inReview();
        assertThat(requested(review.smo(), review.systemId(), priorVa()).get("previousState").asString())
                .isEqualTo("IN_REVIEW");
    }

    @Test
    void anEndorsedVaSubmissionTakesNoExemption() {
        Draft draft = awaitingEndorsement();
        endorsed(vaPoc(), draft.submissionId());

        assertConflict(request(draft.smo(), draft.systemId(), priorVa()), "invalid-transition");
    }

    @Test
    void aVaSubmissionAwaitingEndorsementCanBeFrozen() {
        Draft draft = awaitingEndorsement();

        JsonNode exemption = requested(draft.smo(), draft.systemId(), scheduledVa());

        assertThat(exemption.get("previousState").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(exemption.get("basis").asString()).isEqualTo(SCHEDULED_VA);
        assertConflict(endorse(vaPoc(), draft.submissionId()), "invalid-transition");
    }

    @Test
    void aNotStartedSystemGetsItsVaSubmissionCreatedByTheRequest() {
        draft();
        ApiClient smoB = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        assertThat(inboxRow(smoB, "SYS-F2").get("state").asString()).isEqualTo("NOT_STARTED");

        JsonNode exemption = requested(smoB, system, priorVa());

        assertThat(exemption.get("previousState").asString()).isEqualTo("NOT_STARTED");
        String sub = exemption.get("submissionId").asString();
        JsonNode row = inboxRow(smoB, "SYS-F2");
        assertThat(row.get("state").asString()).isEqualTo(EXEMPTION_REQUESTED);
        assertThat(row.get("submissionId").asString()).isEqualTo(sub);
        assertThat(submission(smoB, sub).get("quarter").asInt()).isEqualTo(3);
        JsonNode audited = entries("EXEMPTION_REQUESTED", system).get(0);
        assertThat(audited.get("detail").get("state_from").asString()).isEqualTo("NOT_STARTED");
        assertConflict(start(smoB, system), "invalid-transition");
    }

    @Test
    void theVaDateMustFallInTheThreeMonthsBeforeTheQuarterOrAfterIt() {
        Draft draft = draft();
        String system = draft.systemId();
        String evidence = "Report ref AC-1.";

        assertInvalidField(request(draft.smo(), system, exemption("OTHER", "2027-07-15", evidence, null)), "basis");
        assertInvalidField(request(draft.smo(), system, exemption(null, "2027-07-15", evidence, null)), "basis");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, null, evidence, null)), "vaDate");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, "15/07/2027", evidence, null)), "vaDate");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, "2027-05-01", evidence, null)), "vaDate");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, "2027-08-02", evidence, null)), "vaDate");
        assertInvalidField(request(draft.smo(), system, exemption(SCHEDULED_VA, "2027-10-31", evidence, null)),
                "vaDate");
        assertInvalidField(request(draft.smo(), system, exemption(SCHEDULED_VA, "2028-02-01", evidence, null)),
                "vaDate");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, "2027-07-15", null, null)), "evidence");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, "2027-07-15", " ​ ", null)),
                "evidence");
        assertInvalidField(request(draft.smo(), system, exemption(PRIOR_VA, "2027-07-15", "x".repeat(4001), null)),
                "evidence");
        assertThat(submission(draft.smo(), draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");

        JsonNode earliest = requested(draft.smo(), system, exemption(PRIOR_VA, "2027-05-02", "x".repeat(4000), null));
        assertThat(earliest.get("vaDate").asString()).isEqualTo("2027-05-02");
    }

    @Test
    void aVaScheduledOnTheLastDayOfTheThreeMonthsAfterTheQuarterIsAccepted() {
        Draft draft = draft();

        JsonNode latest = requested(draft.smo(), draft.systemId(),
                exemption(SCHEDULED_VA, "2028-01-31", "Booked for the last day.", null));

        assertThat(latest.get("vaDate").asString()).isEqualTo("2028-01-31");
    }

    @Test
    void aRequestIsRefusedWhileAnyFileIsStillScanningAndAcceptedOnceItHasFinished() {
        Draft draft = draft();
        String file = uploadedEvidence(draft.smo(), draft.submissionId(), BURP_SUITE, "va.pdf", Samples.pdf())
                .get("id").asString();

        var refused = request(draft.smo(), draft.systemId(), priorVa());

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        JsonNode problem = ApiClient.json(refused);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/files-scanning");
        assertThat(problem.get("reasons").get(0).get("files").get(0).get("fileId").asString()).isEqualTo(file);
        JsonNode audited = entries("EXEMPTION_REQUESTED", draft.systemId()).get(0);
        assertThat(audited.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(audited.get("detail").get("reason").asString()).isEqualTo("files-scanning");
        assertThat(submission(draft.smo(), draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");

        pass();
        pass();

        JsonNode exemption = requested(draft.smo(), draft.systemId(),
                exemption(PRIOR_VA, "2027-07-15", "See the attached report.", file));
        assertThat(exemption.get("evidenceFileId").asString()).isEqualTo(file);
    }

    @Test
    void aRequestIsRefusedWhileARescanIsStillScanning() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String report = settled(draft, DEPENDENCY_CHECK, "dc.json", dcReportAt(SCANNED)).get("id").asString();
        var sent = ReplaceSeam1.replace(draft.smo(), sub, report, "dc-rescan.json",
                dcReportAt("2027-07-27T10:00:00+08:00"));
        assertThat(sent.status()).as(sent.body()).isEqualTo(202);
        String rescan = ApiClient.json(sent).get("id").asString();

        var refused = request(draft.smo(), draft.systemId(), priorVa());

        assertConflict(refused, "files-scanning");
        assertThat(ApiClient.json(refused).get("reasons").get(0).get("files").get(0).get("fileId").asString())
                .isEqualTo(rescan);
        assertThat(entries("EXEMPTION_REQUESTED", draft.systemId()).get(0).get("detail").get("reason").asString())
                .isEqualTo("files-scanning");
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo("DRAFT");
        pass();
        pass();
        assertThat(requested(draft.smo(), draft.systemId(), priorVa()).get("previousState").asString())
                .isEqualTo("DRAFT");
    }

    @Test
    void aPriorVaCannotBeDatedAfterToday() {
        today("2027-07-01");
        Draft draft = draft();

        assertInvalidField(request(draft.smo(), draft.systemId(), priorVa()), "vaDate");
        assertThat(requested(draft.smo(), draft.systemId(),
                exemption(PRIOR_VA, "2027-06-30", "Acme VA, report AC-0.", null)).get("vaDate").asString())
                .isEqualTo("2027-06-30");
    }

    @Test
    void theSystemsCyberManagerMayNotRequestAnExemptionAndNoVaSubmissionIsLeftBehind() {
        Draft draft = draft();
        String other = idOf("SYS-F2");
        arrange.grant("CYBER_MANAGER", SMO_A, draft.systemId());
        arrange.grant("SMO", CM_OTHER, other);

        assertSodConflict(request(draft.smo(), draft.systemId(), priorVa()));
        assertSodConflict(request(signedInAs(CM_OTHER), other, priorVa()));

        assertThat(submission(draft.smo(), draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");
        JsonNode refused = entries("EXEMPTION_REQUESTED", other).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        JsonNode untouched = inboxRow(signedInAs(SMO_B), "SYS-F2");
        assertThat(untouched.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(untouched.get("submissionId").isNull()).as("the refused request created nothing").isTrue();
    }

    @Test
    void theEvidenceFileMustBeAReadySupportingEvidenceFileOfTheSameVaSubmission() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String report = settled(draft, DEPENDENCY_CHECK, "dc.json", dcReportAt(SCANNED)).get("id").asString();
        String rejected = settledEvidence(draft, BURP_SUITE, "fake.pdf", Samples.png()).get("id").asString();
        assertThat(status(draft.smo(), sub, rejected).get("status").asString()).startsWith("REJECTED");

        for (String file : List.of(report, rejected, UUID.randomUUID().toString())) {
            assertInvalidField(request(draft.smo(), draft.systemId(), exemption(PRIOR_VA, "2027-07-15", "See file.", file)),
                    "evidenceFileId");
        }
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo("DRAFT");
    }

    @Test
    void onlyTheSystemsSmoRequestsAnExemption() {
        Draft draft = draft();
        String system = draft.systemId();

        assertThat(request(signedInAs(CM), system, priorVa()).status()).as("not an SMO/Tech Lead").isEqualTo(403);
        assertThat(request(vaPoc(), system, priorVa()).status()).as("not an SMO/Tech Lead").isEqualTo(403);
        assertNotFound(request(signedInAs(SMO_B), system, priorVa()));
        assertNotFound(request(draft.smo(), UUID.randomUUID().toString(), priorVa()));
        assertThat(submission(draft.smo(), draft.submissionId()).get("state").asString()).isEqualTo("DRAFT");
    }

    @Test
    void aClosedCycleTakesNoExemptionRequest() {
        Draft draft = draft();
        today("2028-03-06");
        String inCycle2027 = priorVa().replace("}", ",\"cycle\":2027}");

        assertConflict(request(signedInAs(SMO_A), draft.systemId(), inCycle2027), "invalid-transition");

        JsonNode refused = entries("EXEMPTION_REQUESTED", draft.systemId()).get(0);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("cycle-closed");
    }
}
