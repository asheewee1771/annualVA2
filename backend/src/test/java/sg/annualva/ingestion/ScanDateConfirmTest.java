package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 25 (ticket 23): {@code PUT /api/submissions/{id}/files/{fileId}/metadata} lets the SMO/Tech Lead confirm or correct
 * the scan date and the target a parsed report file names. A target that differs from the file's is flagged, never
 * refused; a scan date is a business date that is not after today. A Burp export names only its export time, which the
 * metadata says. Both reads and the confirmation carry report content: they are data, never logged or audited.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScanDateConfirmTest extends ScanDateSeam1 {

    @Test
    void theScanDateAndTargetThePipelinePreFilledAreOfferedWithTheBusinessDateOfTheScan() {
        Draft draft = draft();
        // 18:00 UTC on 20 July is 02:00 on 21 July in Singapore.
        String id = parsedDc(draft, "late.json", jsonReportOf(
                "{\"name\":\"billing-api\",\"reportDate\":\"2027-07-20T18:00:00Z\"}",
                dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8")));

        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);

        assertThat(metadata.get("scanDate").asString()).isEqualTo("2027-07-20T18:00:00Z");
        assertThat(metadata.get("scanDay").asString()).as("the business date in Singapore").isEqualTo("2027-07-21");
        assertThat(metadata.get("target").asString()).isEqualTo("billing-api");
        assertThat(metadata.get("scanDateIsExportTime").asBoolean()).isFalse();
        assertThat(metadata.get("confirmedScanDate").isNull()).as("nothing confirmed yet").isTrue();
        assertThat(metadata.get("confirmedTarget").isNull()).isTrue();
        assertThat(metadata.get("targetEdited").asBoolean()).isFalse();
        assertThat(metadata.get("scanDateEdited").asBoolean()).isFalse();
    }

    @Test
    void theSmoConfirmsWhatTheFileSaysAndNothingIsFlagged() {
        Draft draft = draft();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        String target = metadata(draft.smo(), draft.submissionId(), id).get("target").asString();

        JsonNode kept = confirmed(draft.smo(), draft.submissionId(), id, "2027-07-20", target);

        assertThat(kept.get("fileId").asString()).isEqualTo(id);
        assertThat(kept.get("confirmedScanDate").asString()).isEqualTo("2027-07-20");
        assertThat(kept.get("confirmedTarget").asString()).isEqualTo(target);
        assertThat(kept.get("targetEdited").asBoolean()).isFalse();
        assertThat(kept.get("scanDateEdited").asBoolean()).as("the day the file says").isFalse();
        assertThat(kept.get("target").asString()).as("what the file says is kept beside it").isEqualTo(target);
        assertThat(metadata(draft.smo(), draft.submissionId(), id)).isEqualTo(kept);
    }

    @Test
    void anEditedTargetIsKeptAndFlaggedNeverRefusedAndConfirmingAgainReplacesIt() {
        Draft draft = draft();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        String fromFile = metadata(draft.smo(), draft.submissionId(), id).get("target").asString();

        JsonNode edited = confirmed(draft.smo(), draft.submissionId(), id, "2027-07-19", "Billing API (production)");

        assertThat(edited.get("confirmedTarget").asString()).isEqualTo("Billing API (production)");
        assertThat(edited.get("targetEdited").asBoolean()).as("flagged for the reviewer").isTrue();
        assertThat(edited.get("target").asString()).isEqualTo(fromFile);
        assertThat(edited.get("confirmedScanDate").asString()).as("a corrected scan date").isEqualTo("2027-07-19");
        assertThat(edited.get("scanDateEdited").asBoolean()).as("flagged, never refused").isTrue();

        JsonNode back = confirmed(draft.smo(), draft.submissionId(), id, "2027-07-20", fromFile);
        assertThat(back.get("targetEdited").asBoolean()).isFalse();
        assertThat(back.get("scanDateEdited").asBoolean()).isFalse();
        assertThat(back.get("confirmedScanDate").asString()).isEqualTo("2027-07-20");

        JsonNode entries = entries("FILE_METADATA_CONFIRMED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("detail").get("date_changed").asBoolean()).as("the latest: back as the file says")
                .isFalse();
        assertThat(entries.get(0).get("detail").get("target_changed").asBoolean()).isFalse();
        assertThat(entries.get(1).get("detail").get("date_changed").asBoolean()).isTrue();
        assertThat(entries.get(1).get("detail").get("target_changed").asBoolean()).isTrue();
    }

    @Test
    void aTargetTheFileDidNotNameIsAnEditedOne() {
        Draft draft = draft();
        String id = parsedDc(draft, "nameless.json",
                jsonReportOf("{\"reportDate\":\"2027-07-20T10:00:00Z\"}",
                        dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8")));
        assertThat(metadata(draft.smo(), draft.submissionId(), id).get("target").isNull()).isTrue();

        JsonNode kept = confirmed(draft.smo(), draft.submissionId(), id, "2027-07-20", "billing-api");

        assertThat(kept.get("targetEdited").asBoolean()).isTrue();
    }

    @Test
    void aBurpExportOffersItsExportTimeOnlyAndTheSmoGivesTheDayTheScanWasMade() {
        Draft draft = draft();
        JsonNode file = settled(draft, BURP_SUITE, "burp.xml", Samples.burp());
        String id = file.get("id").asString();

        JsonNode offered = metadata(draft.smo(), draft.submissionId(), id);
        assertThat(offered.get("scanDateIsExportTime").asBoolean()).isTrue();
        assertThat(offered.get("scanDay").asString()).isEqualTo("2027-07-20");

        JsonNode kept = confirmed(draft.smo(), draft.submissionId(), id, "2027-07-18", "https://throwaway.invalid");
        assertThat(kept.get("confirmedScanDate").asString()).isEqualTo("2027-07-18");
        assertThat(kept.get("scanDateIsExportTime").asBoolean()).as("still says what the file holds").isTrue();
        assertThat(kept.get("targetEdited").asBoolean()).isFalse();
        assertThat(kept.get("scanDateEdited").asBoolean()).as("not the export day").isTrue();

        JsonNode exportDay = confirmed(draft.smo(), draft.submissionId(), id, "2027-07-20", "https://throwaway.invalid");
        assertThat(exportDay.get("scanDateEdited").asBoolean()).as("the export day: one rule for every format").isFalse();
    }

    @Test
    void theScanDateIsADayNotAfterTodayAndWithinTheLastTenYearsAndTheTargetOneLineOfAtMost200() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        assertInvalidField(confirm(draft.smo(), sub, id, confirmation(null, "app")), "scanDate");
        for (String wrong : List.of("", "2027-7-20", "20/07/2027", "2027-07-20T10:00:00", "2027-02-30", "+12027-07-20",
                "yesterday")) {
            assertInvalidField(confirm(draft.smo(), sub, id, confirmation(wrong, "app")), "scanDate");
        }
        assertInvalidField(confirm(draft.smo(), sub, id, confirmation("2027-08-03", "app")), "scanDate");
        assertInvalidField(confirm(draft.smo(), sub, id, confirmation("2017-08-01", "app")), "scanDate");
        assertInvalidField(confirm(draft.smo(), sub, id, confirmation("2027-07-20", null)), "target");
        assertInvalidField(confirm(draft.smo(), sub, id, confirmation("2027-07-20", " \u0007\u200B\t ")), "target");
        assertInvalidField(confirm(draft.smo(), sub, id, confirmation("2027-07-20", "x".repeat(201))), "target");
        assertInvalidField(confirm(draft.smo(), sub, id, confirmation(null, null)), "scanDate");
        assertThat(confirm(draft.smo(), sub, id, "{\"scanDate\":").status()).isEqualTo(400);
        assertThat(metadata(draft.smo(), sub, id).get("confirmedScanDate").isNull()).as("nothing was kept").isTrue();

        assertThat(confirmed(draft.smo(), sub, id, "2027-08-02", "x".repeat(200)).get("confirmedScanDate").asString())
                .as("today, and the longest target").isEqualTo("2027-08-02");
        assertThat(confirmed(draft.smo(), sub, id, "2017-08-02", "app").get("confirmedScanDate").asString())
                .as("ten years ago today").isEqualTo("2017-08-02");
        assertThat(confirmed(draft.smo(), sub, id, "2027-07-20", "  billing\u202E api\n(prod)\u0000 ")
                .get("confirmedTarget").asString()).as("cleaned to one line, as every short text").isEqualTo("billing api (prod)");
    }

    @Test
    void theScanDateIsCheckedAgainstTodayInSingaporeNotInUtc() {
        // 20:00 UTC on 1 August is 04:00 on 2 August in Singapore: today there is 2 August.
        clock.set(java.time.Instant.parse("2027-08-01T20:00:00Z"));
        Draft draft = draft();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        assertThat(confirmed(draft.smo(), draft.submissionId(), id, "2027-08-02", "app").get("confirmedScanDate")
                .asString()).isEqualTo("2027-08-02");
        assertInvalidField(confirm(draft.smo(), draft.submissionId(), id, confirmation("2027-08-03", "app")), "scanDate");
    }

    @Test
    void onlyTheSmoOfTheSystemConfirmsAndOnlyAParsedReportFileOfThisVaSubmission() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        String body = confirmation("2027-07-20", "app");

        for (ApiClient other : List.of(signedInAs(CM), vaPoc())) {
            assertThat(confirm(other, sub, id, body).status()).isEqualTo(403);
        }
        var stranger = confirm(signedInAs(SMO_B), sub, id, body);
        var missing = confirm(draft.smo(), sub, "00000000-0000-4000-8000-000000000000", body);
        assertNotFound(stranger);
        assertNotFound(missing);
        assertThat(ApiClient.json(stranger).get("detail")).isEqualTo(ApiClient.json(missing).get("detail"));
        assertThat(anotherBrowser().withCsrf().putJson(metadataOf(sub, id), body).status()).isEqualTo(401);

        String rejected = settledDc(draft, "page.html", Samples.html()).get("id").asString();
        String evidence = settledEvidence(draft, DEPENDENCY_CHECK, "memo.pdf", Samples.pdf()).get("id").asString();
        String scanning = uploaded(draft.smo(), sub, DEPENDENCY_CHECK, "dc.json", Samples.dependencyCheckJson())
                .get("id").asString();
        for (String notParsed : List.of(rejected, evidence, scanning)) {
            assertNotFound(confirm(draft.smo(), sub, notParsed, body));
        }
        ApiClient smoB = signedInAs(SMO_B);
        Draft other = new Draft(smoB, idOf("SYS-F2"), started(smoB, idOf("SYS-F2")).get("id").asString());
        assertNotFound(confirm(draft.smo(), other.submissionId(), id, body));
        assertNotFound(confirm(smoB, other.submissionId(), id, body));

        assertThat(metadata(draft.smo(), sub, id).get("confirmedScanDate").isNull()).isTrue();
        assertThat(entries("FILE_METADATA_CONFIRMED", draft.systemId())).as("a 403 or 404 writes nothing here").isEmpty();
    }

    @Test
    void onlyInDraftOrReturnedForReworkAndTheRefusalIsAudited() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        String body = confirmation("2027-07-20", "app");

        for (String state : List.of("IN_REVIEW", "AWAITING_ENDORSEMENT", "ENDORSED", "EXEMPTION_REQUESTED", "EXEMPTED",
                "WITHDRAWN", "NOT_ENDORSED")) {
            arrange.submissionState(sub, state);
            assertConflict(confirm(draft.smo(), sub, id, body), "invalid-transition");
        }
        assertThat(metadata(draft.smo(), sub, id).get("confirmedScanDate").isNull()).isTrue();
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");
        confirmed(draft.smo(), sub, id, "2027-07-20", "app");

        JsonNode entries = entries("FILE_METADATA_CONFIRMED", draft.systemId());
        assertThat(entries).hasSize(8);
        for (JsonNode entry : entries) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
                assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(id);
            }
        }
    }

    @Test
    void aFileARescanReplacedIsHistoryAndIsNotConfirmedAgain() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = parsedDc(draft, "first.json", firstScan());
        String rescan = replacedAndSettled(draft, first, "rescan.json", rescan()).get("id").asString();

        assertConflict(confirm(draft.smo(), sub, first, confirmation("2027-07-20", "app")), "file-superseded");
        assertThat(confirmed(draft.smo(), sub, rescan, "2027-07-20", "app").get("confirmedScanDate").asString())
                .isEqualTo("2027-07-20");

        JsonNode entries = entries("FILE_METADATA_CONFIRMED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(1).get("detail").get("reason").asString()).isEqualTo("superseded");
    }

    @Test
    void theConfirmationIsAuditedWithTheFileIdOnlyAndWhatThePersonTypedIsNeverLoggedOrEchoed(CapturedOutput output) {
        Draft draft = draft();
        String sub = draft.submissionId();
        String id = parsedDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        confirmed(draft.smo(), sub, id, "2027-07-20", "billing " + SENTINEL);
        List<String> bodies = List.of(
                confirm(draft.smo(), sub, id, confirmation(SENTINEL, "app")).body(),
                confirm(draft.smo(), sub, id, confirmation("2027-07-20", SENTINEL + "x".repeat(200))).body(),
                confirm(signedInAs(SMO_B), sub, id, confirmation("2027-07-20", SENTINEL)).body());

        JsonNode entries = entries("FILE_METADATA_CONFIRMED", draft.systemId());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(entry.get("submissionId").asString()).isEqualTo(sub);
        assertThat(entry.get("detail").propertyNames()).as("flags, never the values")
                .containsExactlyInAnyOrder("file_id", "date_changed", "target_changed");
        assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(id);
        assertThat(entry.get("detail").get("date_changed").isBoolean()).isTrue();
        assertThat(entry.get("detail").get("date_changed").asBoolean()).isFalse();
        assertThat(entry.get("detail").get("target_changed").isBoolean()).isTrue();
        assertThat(entry.get("detail").get("target_changed").asBoolean()).isTrue();
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL).contains("file-metadata-confirmed");
        assertThat(String.join("\n", bodies)).as("error bodies").doesNotContain(SENTINEL);
        assertThat(metadata(signedInAs(CM), sub, id).get("confirmedTarget").asString())
                .as("the Cyber Manager reads it as data").isEqualTo("billing " + SENTINEL);
    }

    private static void assertConflict(ApiClient.Response response, String type) {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/" + type);
    }
}
