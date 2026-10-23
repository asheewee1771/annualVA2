package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Story 26 (ticket 23): the readiness check names, by file, every report file more than 90 days old at today's business
 * date, so that submitting (ticket 25) can be refused with a message naming it, and every report file with no scan date to
 * judge (none in the file and none given, or only a Burp export time). The scan date is the one the SMO/Tech Lead confirmed,
 * else the business date of the one the file names. The age is read from the controllable clock: these tests move the
 * clock, never the reports. Only live report files count: not a rejected one, a replaced one or supporting evidence.
 *
 * <p>The reports here were scanned on 20 February 2027 and uploaded on 1 March; the clock then moves to late May. Everything
 * happens before the default clock (2 August 2027), so the audit entries these tests write are never newer than those of
 * the tests that read the latest page of the audit log.
 */
class FileAgeReadinessTest extends ScanDateSeam1 {

    /** When the reports were scanned, in Singapore. */
    private static final String SCANNED = "2027-02-20T10:00:00+08:00";
    /** The same day as a Burp export time (a Saturday). */
    private static final String EXPORTED = "Sat Feb 20 10:00:00 SGT 2027";

    @BeforeEach
    void uploadDay() {
        today("2027-03-01");
    }

    /** The VA Submission's readiness as its SMO/Tech Lead reads it on this business day, signed in afresh. */
    private JsonNode readinessOn(Draft draft, String businessDay) {
        today(businessDay);
        return readiness(signedInAs(SMO_A), draft.submissionId());
    }

    @Test
    void aVaSubmissionWithNoFilesHasNothingTooOldOrUndated() {
        Draft draft = draft();

        JsonNode readiness = readiness(draft.smo(), draft.submissionId());

        assertThat(readiness.get("tooOld")).isEmpty();
        assertThat(readiness.get("undated")).isEmpty();
    }

    @Test
    void aFileIsTooOldOnlyOnceItIsMoreThan90DaysOldAtTodaysBusinessDate() {
        Draft draft = draft();
        String id = parsedDc(draft, "dc.json", dcReportAt(SCANNED));

        assertThat(readinessOn(draft, "2027-05-21").get("tooOld")).as("90 days old").isEmpty();
        JsonNode tooOld = readinessOn(draft, "2027-05-22").get("tooOld");

        assertThat(named(tooOld)).as("91 days old").containsExactly("dc.json (DEPENDENCY_CHECK)");
        assertThat(tooOld.get(0).get("fileId").asString()).isEqualTo(id);
        assertThat(tooOld.get(0).propertyNames()).as("no date: the scan date is report content")
                .containsExactlyInAnyOrder("fileId", "name", "reportType");
    }

    @Test
    void theAgeIsJudgedOnTheBusinessDateInSingapore() {
        Draft draft = draft();
        parsedDc(draft, "dc.json", dcReportAt(SCANNED));

        // 17:00 UTC on 21 May is 01:00 on 22 May in Singapore: the file is 91 days old there.
        clock.set(java.time.Instant.parse("2027-05-21T17:00:00Z"));

        assertThat(named(readiness(signedInAs(SMO_A), draft.submissionId()).get("tooOld"))).hasSize(1);
    }

    @Test
    void theConfirmedScanDateIsTheOneJudgedAndEveryFileTooOldIsNamed() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String older = parsedDc(draft, "older.json", dcReportAt(SCANNED));
        String newer = parsedDc(draft, "newer.json", dcReportAt(SCANNED));
        String third = parsedDc(draft, "third.json", dcReportAt(SCANNED));
        confirmed(draft.smo(), sub, older, "2027-02-19", "app");
        confirmed(draft.smo(), sub, newer, "2027-02-28", "app");

        assertThat(named(readinessOn(draft, "2027-05-21").get("tooOld"))).as("older was confirmed a day earlier")
                .containsExactly("older.json (DEPENDENCY_CHECK)");
        JsonNode tooOld = readinessOn(draft, "2027-05-22").get("tooOld");

        assertThat(named(tooOld)).as("in the order they were uploaded; newer was confirmed later than its file says")
                .containsExactly("older.json (DEPENDENCY_CHECK)", "third.json (DEPENDENCY_CHECK)");
        assertThat(tooOld.get(1).get("fileId").asString()).isEqualTo(third);
    }

    @Test
    void aBurpExportTimeIsNotAScanDateSoTheFileIsUndatedUntilTheSmoGivesOne() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String burp = settled(draft, BURP_SUITE, "burp.xml", burpExportAt(EXPORTED, BURP_ISSUE)).get("id").asString();
        String undated = parsedDc(draft, "undated.json", dcReportAt(null));
        parsedDc(draft, "dated.json", dcReportAt(SCANNED));

        JsonNode readiness = readiness(draft.smo(), sub);
        assertThat(named(readiness.get("undated")))
                .containsExactly("burp.xml (BURP_SUITE)", "undated.json (DEPENDENCY_CHECK)");
        assertThat(readiness.get("undated").get(0).get("fileId").asString()).isEqualTo(burp);
        JsonNode later = readinessOn(draft, "2027-06-30");
        assertThat(named(later.get("undated"))).as("never too old: it has no date").hasSize(2);
        assertThat(named(later.get("tooOld"))).containsExactly("dated.json (DEPENDENCY_CHECK)");

        today("2027-03-01");
        ApiClient smo = signedInAs(SMO_A);
        confirmed(smo, sub, burp, "2027-02-18", "https://throwaway.invalid");
        confirmed(smo, sub, undated, "2027-02-01", "app");
        assertThat(readiness(smo, sub).get("undated")).isEmpty();
        assertThat(named(readinessOn(draft, "2027-05-20").get("tooOld"))).as("the dates the SMO gave are judged")
                .containsExactly("burp.xml (BURP_SUITE)", "undated.json (DEPENDENCY_CHECK)");
    }

    @Test
    void onlyLiveReportFilesCountNotRejectedReplacedOrEvidence() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = parsedDc(draft, "first.json", dcReportAt(SCANNED));
        replacedAndSettled(draft, first, "rescan.json", dcReportAt(SCANNED));
        settledDc(draft, "page.html", Samples.html());
        settledEvidence(draft, DEPENDENCY_CHECK, "memo.pdf", Samples.pdf());
        uploaded(draft.smo(), sub, BURP_SUITE, "scanning.xml", Samples.burp());

        JsonNode readiness = readinessOn(draft, "2027-06-30");

        assertThat(named(readiness.get("tooOld"))).as("the rescan, not the file it replaced")
                .containsExactly("rescan.json (DEPENDENCY_CHECK)");
        assertThat(readiness.get("undated")).as("a file still being checked has no date yet").isEmpty();
    }

    @Test
    void onlyThisVaSubmissionsFilesAreNamedAndACyberManagersReadIsStillAudited() {
        Draft draft = draft();
        parsedDc(draft, "mine.json", dcReportAt(SCANNED));
        ApiClient smoB = signedInAs(SMO_B);
        Draft other = new Draft(smoB, idOf("SYS-F2"), started(smoB, idOf("SYS-F2")).get("id").asString());
        settledDc(other, "theirs.json", dcReportAt(SCANNED));

        today("2027-06-30");
        assertThat(named(readiness(signedInAs(SMO_A), draft.submissionId()).get("tooOld")))
                .containsExactly("mine.json (DEPENDENCY_CHECK)");
        assertThat(named(readiness(signedInAs(CM), draft.submissionId()).get("tooOld")))
                .containsExactly("mine.json (DEPENDENCY_CHECK)");
        assertThat(entries("READINESS_VIEWED", draft.systemId())).hasSize(1);
    }
}
