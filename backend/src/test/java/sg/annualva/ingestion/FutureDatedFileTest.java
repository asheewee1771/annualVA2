package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 26 (ticket 23): a file whose scan date is after today's business date in Singapore is rejected by the pipeline as
 * REJECTED_FUTURE_DATED. The age check runs last, after the parse and the pre-fill of the scan date and target, so a file
 * the parser refuses is refused for that, and a future-dated file passed the schema check first. Its Findings are not kept.
 */
class FutureDatedFileTest extends ScanDateSeam1 {

    @Test
    void aFileScannedAfterTodayIsRejectedAfterTheParseAndKeepsNothing() {
        Draft draft = draft();

        JsonNode file = settledDc(draft, "tomorrow.json", dcReportAt("2027-08-03T09:00:00+08:00"));

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_FUTURE_DATED");
        assertThat(file.get("rejectCode").asString()).isEqualTo("FUTURE_DATED");
        assertThat(file.get("message").asString()).contains("after today").contains("rejected");
        assertThat(stages(file)).as("quarantine, scan, type, schema and parse, then the age check").containsExactly(
                "QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK", "REJECTED_FUTURE_DATED");
        assertThat(total(draft, draft.smo())).as("its Findings are not kept").isZero();
        assertThat(fileStore.count(FileStore.Zone.CLEAN)).as("a rejected file keeps no bytes").isZero();
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).isZero();
        String id = file.get("id").asString();
        assertNotFound(draft.smo().get(metadataOf(draft.submissionId(), id)));
        JsonNode rejected = entries("FILE_REJECTED", draft.systemId());
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).get("detail").get("reject_code").asString()).isEqualTo("future-dated");
        assertThat(rejected.get(0).get("detail").get("file_id").asString()).isEqualTo(id);
    }

    @Test
    void todayIsTheBusinessDateInSingaporeNotTheDateInUtc() {
        Draft draft = draft();

        // 16:00 UTC on 2 August is midnight on 3 August in Singapore: tomorrow there, though still today in UTC.
        JsonNode tomorrow = settledDc(draft, "midnight.json", dcReportAt("2027-08-02T16:00:00Z"));
        JsonNode lateToday = settledDc(draft, "late-today.json", dcReportAt("2027-08-02T15:59:59Z"));

        assertThat(tomorrow.get("status").asString()).isEqualTo("REJECTED_FUTURE_DATED");
        assertThat(lateToday.get("status").asString()).as("a scan made today is not in the future").isEqualTo("PARSED");
    }

    @Test
    void theAgeIsReadFromTheControllableClockSoTheSameFileIsAcceptedOnceItsDayHasCome() {
        today("2027-07-19");
        Draft early = draft();
        JsonNode beforeTheScan = settledDc(early, "dc.xml", Samples.dependencyCheckSampleXml());
        assertThat(beforeTheScan.get("status").asString()).isEqualTo("REJECTED_FUTURE_DATED");

        today("2027-07-20");
        JsonNode onTheDay = settledDc(new Draft(signedInAs(SMO_A), early.systemId(), early.submissionId()), "dc.xml",
                Samples.dependencyCheckSampleXml());
        assertThat(onTheDay.get("status").asString()).isEqualTo("PARSED");
    }

    @Test
    void theParseComesBeforeTheAgeCheckSoAFutureDatedFileTheParserRefusesIsASchemaRejection() {
        Draft draft = draft();
        String noHost = "<issue><type>1</type><name>No host</name><path>/</path></issue>";

        JsonNode refused = settled(draft, BURP_SUITE, "no-host.xml", burpExportAt("Wed Aug 04 10:00:00 SGT 2027", noHost));
        JsonNode future = settled(draft, BURP_SUITE, "future.xml", burpExportAt("Wed Aug 04 10:00:00 SGT 2027", BURP_ISSUE));

        assertThat(refused.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(stages(refused)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK",
                "REJECTED_SCHEMA");
        assertThat(future.get("status").asString()).as("an export time after today is in the future too")
                .isEqualTo("REJECTED_FUTURE_DATED");
    }

    @Test
    void aFileThatNamesNoScanDateIsNotFutureDated() {
        Draft draft = draft();

        JsonNode undated = settledDc(draft, "undated.json", dcReportAt(null));

        assertThat(undated.get("status").asString()).isEqualTo("PARSED");
    }

    @Test
    void aFutureDatedRescanIsRejectedAndChangesNothing() {
        Draft draft = draft();
        String first = parsedDc(draft, "first.json", firstScan());
        int before = total(draft, draft.smo());

        JsonNode rescan = replacedAndSettled(draft, first, "rescan.json", dcReportAt("2027-08-09T09:00:00+08:00"));

        assertThat(rescan.get("status").asString()).isEqualTo("REJECTED_FUTURE_DATED");
        JsonNode old = status(draft.smo(), draft.submissionId(), first);
        assertThat(old.get("supersededBy").isNull()).as("the file it was to replace stays as it was").isTrue();
        assertThat(total(draft, draft.smo())).isEqualTo(before);
    }
}
