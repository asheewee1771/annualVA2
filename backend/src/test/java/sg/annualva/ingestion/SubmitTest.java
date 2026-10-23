package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Stories 40 and 41 (ticket 25): the SMO/Tech Lead ticks the fixed Declaration and submits a VA Submission whose every
 * Finding has a proposed status and whose Scan Reports are all provided. The submit stores the exact wording accepted, with
 * who accepted it and when, moves the VA Submission to In Review, records the submitting step, audits it and publishes the
 * event the notification tickets consume; afterwards nothing the SMO/Tech Lead prepares can be changed.
 */
class SubmitTest extends SubmitSeam1 {

    @Test
    void theDeclarationIsTheCurrentVersionAndItsWordingMarkedAsAPlaceholderUntilAgreed() {
        draft();

        for (ApiClient reader : new ApiClient[] {signedInAs(SMO_A), signedInAs(CM), vaPoc()}) {
            var response = reader.get(DECLARATION_TEXT);

            assertThat(response.status()).as(response.body()).isEqualTo(200);
            JsonNode declaration = ApiClient.json(response);
            assertThat(declaration.get("version").asInt()).isEqualTo(1);
            assertThat(declaration.get("placeholder").asBoolean()).isTrue();
            assertThat(declaration.get("text").asString()).startsWith("PLACEHOLDER WORDING, NOT YET AGREED.");
        }
        assertThat(anotherBrowser().get(DECLARATION_TEXT).status()).as("signed out").isEqualTo(401);
    }

    @Test
    void theCurrentDeclarationIsTheHighestVersionAndOnlyItIsAcceptedAndKept() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        int agreed = arrange.declarationVersion();

        JsonNode shown = ApiClient.json(draft.smo().get(DECLARATION_TEXT));
        var earlier = submit(draft.smo(), sub, declaration(true, 1));
        JsonNode answered = submitted(draft.smo(), sub);

        assertThat(agreed).isGreaterThan(1);
        assertThat(shown.get("version").asInt()).isEqualTo(agreed);
        assertThat(shown.get("placeholder").asBoolean()).isFalse();
        assertThat(codes(refusalReasons(earlier))).as("the placeholder is no longer current")
                .containsExactly("declaration-changed");
        assertThat(answered.get("declaration").get("version").asInt()).isEqualTo(agreed);
        assertThat(answered.get("declaration").get("text").asString()).isEqualTo(shown.get("text").asString());
    }

    @Test
    void aReadyVaSubmissionHasNothingInTheWayAndSubmittingMovesItToInReviewWithTheDeclarationStored() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        assertThat(blockers(draft.smo(), sub)).as("ready").isEmpty();
        String wording = ApiClient.json(draft.smo().get(DECLARATION_TEXT)).get("text").asString();
        clock.advance(Duration.ofMinutes(7));

        JsonNode answered = submitted(draft.smo(), sub);

        assertThat(answered.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(answered.get("submittedAt").asString()).isEqualTo(clock.instant().toString());
        JsonNode declaration = answered.get("declaration");
        assertThat(declaration.get("version").asInt()).isEqualTo(1);
        assertThat(declaration.get("text").asString()).as("the exact wording accepted").isEqualTo(wording);
        assertThat(declaration.get("acceptedBy").asString()).isEqualTo(userId(draft.smo()));
        assertThat(declaration.get("acceptedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(submission(draft.smo(), sub)).as("as read back").isEqualTo(answered);
        assertThat(answered.get("acted")).as("the submitting step, recorded once for the SMO who started it")
                .hasSize(1);
        assertThat(answered.get("acted").get(0).get("step").asString()).isEqualTo("SUBMIT");
    }

    @Test
    void theSubmitIsAuditedWithTheStatesAndTheDeclarationVersionAndPublishedAfterItCommits() {
        Draft draft = readyDraft();

        submitted(draft.smo(), draft.submissionId());

        JsonNode entries = entries("SUBMISSION_SUBMITTED", draft.systemId());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(draft.smo()));
        assertThat(entry.get("detail").propertyNames())
                .containsExactlyInAnyOrder("state_from", "state_to", "declaration_version");
        assertThat(entry.get("detail").get("state_from").asString()).isEqualTo("DRAFT");
        assertThat(entry.get("detail").get("state_to").asString()).isEqualTo("IN_REVIEW");
        assertThat(entry.get("detail").get("declaration_version").asInt()).isEqualTo(1);
        assertThat(entry.toString()).as("never the wording").doesNotContain("PLACEHOLDER");

        assertThat(events.published()).hasSize(1);
        SubmissionEvent event = events.published().getFirst();
        assertThat(event.submissionId()).isEqualTo(UUID.fromString(draft.submissionId()));
        assertThat(event.systemId()).isEqualTo(UUID.fromString(draft.systemId()));
        assertThat(event.from().name()).isEqualTo("DRAFT");
        assertThat(event.to().name()).isEqualTo("IN_REVIEW");
        assertThat(event.actorId()).isEqualTo(UUID.fromString(userId(draft.smo())));
        assertThat(event.at()).isEqualTo(clock.instant());
    }

    @Test
    void aVaSubmissionReturnedForReworkIsSubmittedAgainWithASecondDeclaration() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        submitted(draft.smo(), sub);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");
        clock.advance(Duration.ofDays(1));

        JsonNode again = submitted(signedInAs(SMO_A), sub);

        assertThat(again.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(again.get("submittedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(again.get("declaration").get("acceptedAt").asString()).isEqualTo(clock.instant().toString());
        JsonNode entries = entries("SUBMISSION_SUBMITTED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("detail").get("state_from").asString()).isEqualTo("RETURNED_FOR_REWORK");
        assertThat(events.published()).hasSize(2);
    }

    @Test
    void onceSubmittedNothingTheSmoPreparesCanBeChangedAndASecondSubmitIsRefused() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        String dc = ApiClient.json(draft.smo().get(filesOf(sub))).get(0).get("id").asString();
        String finding = findings(draft.smo(), sub).get("items").get(0).get("id").asString();
        submitted(draft.smo(), sub);

        assertConflict(propose(draft.smo(), finding, falsePositive("Changed my mind.")), "invalid-transition");
        assertConflict(upload(draft.smo(), sub, DEPENDENCY_CHECK, MACHINE_READABLE, "late.json", dcReportAt(SCANNED)),
                "invalid-transition");
        assertConflict(confirm(draft.smo(), sub, dc, confirmation("2027-07-21", "app")), "invalid-transition");
        assertConflict(setMode(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "Still none.")), "invalid-transition");
        assertConflict(enter(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Late", "SC-LATE-1", "late", "LOW")),
                "invalid-transition");
        assertConflict(draft.smo().postJson(SUBMISSIONS + "/" + sub + "/findings/bulk-proposals",
                "{\"findingIds\":[" + quoted(finding) + "],\"status\":\"FALSE_POSITIVE\",\"justification\":\"Late.\"}"),
                "invalid-transition");
        assertConflict(replace(draft.smo(), sub, dc, "rescan.json", dcReportAt(SCANNED)), "invalid-transition");
        var again = submitAccepting(draft.smo(), sub);
        assertConflict(again, "invalid-transition");

        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo("IN_REVIEW");
        JsonNode entries = entries("SUBMISSION_SUBMITTED", draft.systemId());
        assertThat(entries.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("state-not-submittable");
        assertThat(events.published()).as("only the one submit").hasSize(1);
    }

    @Test
    void aSecureConfigurationReportFileThatWasReadCountsAsProvided() {
        draft();
        Draft other = draftOfOtherSystem();
        JsonNode file = settled(other, SECURE_CONFIGURATION, "results.xml", Samples.xccdfResults());
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        modeSet(other.smo(), other.submissionId(), DEPENDENCY_CHECK, mode(NOT_APPLICABLE, "No libraries."));
        modeSet(other.smo(), other.submissionId(), BURP_SUITE, mode(NOT_APPLICABLE, "No web interface."));
        proposeEvery(other);

        assertThat(submitted(other.smo(), other.submissionId()).get("state").asString()).isEqualTo("IN_REVIEW");
    }

    @Test
    void onlyTheSystemsOwnSmoSubmitsIt() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        int version = currentVersion(draft.smo());

        var otherSmo = submit(signedInAs(SMO_B), sub, declaration(true, version));
        var manager = submit(signedInAs(CM), sub, declaration(true, version));
        var poc = submit(vaPoc(), sub, declaration(true, version));
        var missing = submit(draft.smo(), UUID.randomUUID().toString(), declaration(true, version));
        var otherSmoUnticked = submit(signedInAs(SMO_B), sub, declaration(false, version));

        assertNotFound(otherSmo);
        assertNotFound(otherSmoUnticked);
        assertThat(entries("SUBMISSION_SUBMITTED", draft.systemId()))
                .as("not even a refusal is recorded against another System's VA Submission").isEmpty();
        assertThat(manager.status()).isEqualTo(403);
        assertThat(poc.status()).isEqualTo(403);
        assertNotFound(missing);
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo("DRAFT");
        assertThat(events.published()).isEmpty();
    }

    /** SYS-F2's VA Submission, started by its SMO/Tech Lead SMO_B. */
    private Draft draftOfOtherSystem() {
        ApiClient smo = signedInAs(SMO_B);
        String system = idOf("SYS-F2");
        return new Draft(smo, system, started(smo, system).get("id").asString());
    }
}
