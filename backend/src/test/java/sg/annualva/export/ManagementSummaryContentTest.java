package sg.annualva.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;

/**
 * Story 81 (ticket 44): the management summary holds counts by Quarter and state with Endorsed Late, and a by-System
 * table with the SMO/Tech Lead, the state, Overdue and the number of open Deviations. Withdrawn VA Submissions and
 * Quarters Handled outside the app are left out, and no vulnerability detail is in it. The default clock is the first day
 * of Q3 of VA Cycle 2027, its first live Quarter.
 */
class ManagementSummaryContentTest extends ExportSeam1 {

    @Test
    void theSummaryCountsEachQuarterByStateAndListsEachSystemWithItsSmoStateOverdueAndOpenDeviations() {
        registered(listed("SYS-C1", "Not started", SMO_A, CM, "Q3"), listed("SYS-C2", "Draft", SMO_A, CM, "Q3"),
                listed("SYS-C3", "In Review", SMO_A, CM_OTHER, "Q3"), listed("SYS-C4", "Endorsed late", SMO_B, CM, "Q3"),
                listed("SYS-C5", "Endorsed", SMO_B, CM, "Q3"), listed("SYS-C6", "Withdrawn", SMO_B, CM, "Q3"),
                listed("SYS-C7", "Next Quarter", SMO_A, CM, "Q4"), listed("SYS-C8", "Outside", SMO_A, CM, "Q1"));
        ApiClient smoA = signedInAs(SMO_A);
        ApiClient smoB = signedInAs(SMO_B);
        String draft = started(smoA, "SYS-C2");
        List<String> entered = enteredFindings(smoA, draft, 2);
        proposedAs(smoA, entered.get(0), "{\"status\":\"RISK_ACCEPTED\",\"reason\":\"Low exposure.\","
                + "\"approverName\":\"Approver\",\"approverRole\":\"Director\"}");
        proposedAs(smoA, entered.get(1), "{\"status\":\"REMEDIATED\",\"evidence\":\"Upgraded.\"}");
        proposedAs(smoA, sentinelFinding(smoA, draft),
                "{\"status\":\"REMEDIATION_PLANNED\",\"plan\":\"Next release.\",\"targetDate\":\"2027-12-31\"}");
        arrange.submissionState(started(smoA, "SYS-C3"), "IN_REVIEW");
        String late = started(smoB, "SYS-C4");
        arrange.submissionState(late, "ENDORSED");
        arrange.endorsedLate(late);
        arrange.submissionState(started(smoB, "SYS-C5"), "ENDORSED");
        arrange.submissionState(started(smoB, "SYS-C6"), "WITHDRAWN");
        today("2027-08-16");
        arrange.overdueSweep();

        XSSFWorkbook workbook = summaryOfTheCycle(vaPoc());

        List<List<String>> summary = rows(workbook, "Summary");
        assertThat(summary.get(0)).containsExactly("Confidential");
        assertThat(summary.get(1)).containsExactly("Management summary, VA Cycle 2027");
        assertThat(summary.get(2)).containsExactly("As at 2027-08-16");
        assertThat(rowStarting(summary, "Quarter")).containsExactly("Quarter", "Starts", "Ends", "Not Started", "Draft",
                "In Review", "Returned for Rework", "Awaiting Endorsement", "Endorsed", "Endorsed Late",
                "Exemption Requested", "Exempted", "Not Endorsed", "Exemption Lapsed", "Total");
        assertThat(rowStarting(summary, "Q3")).containsExactly("Q3", "2027-08-02", "2027-10-31", "1", "1", "1", "0", "0",
                "2", "1", "0", "0", "0", "0", "5");
        assertThat(rowStarting(summary, "Q4")).containsExactly("Q4", "2027-11-01", "2028-02-06", "1", "0", "0", "0", "0",
                "0", "0", "0", "0", "0", "0", "1");
        assertThat(summary).as("Quarters Handled outside the app are left out")
                .noneMatch(row -> !row.isEmpty() && (row.getFirst().equals("Q1") || row.getFirst().equals("Q2")));

        List<List<String>> systems = rows(workbook, "Systems");
        assertThat(systems.get(0)).containsExactly("Confidential");
        assertThat(systems.get(1)).containsExactly("System ID", "System Name", "Quarter", "SMO/Tech Lead", "State",
                "Overdue", "Endorsed Late", "Exemption Lapsed", "Open Deviations");
        assertThat(systems.subList(2, systems.size())).containsExactly(
                List.of("SYS-C1", "Not started", "Q3", SMO_A, "Not Started", "Yes", "No", "No", "0"),
                List.of("SYS-C2", "Draft", "Q3", SMO_A, "Draft", "Yes", "No", "No", "2"),
                List.of("SYS-C3", "In Review", "Q3", SMO_A, "In Review", "No", "No", "No", "0"),
                List.of("SYS-C4", "Endorsed late", "Q3", SMO_B, "Endorsed", "No", "Yes", "No", "0"),
                List.of("SYS-C5", "Endorsed", "Q3", SMO_B, "Endorsed", "No", "No", "No", "0"),
                List.of("SYS-C7", "Next Quarter", "Q4", SMO_A, "Not Started", "No", "No", "No", "0"));

        String all = everything(workbook);
        assertThat(all).as("no vulnerability detail").doesNotContain(SENTINEL).doesNotContain("Entered 1")
                .doesNotContain("CVE-2027").doesNotContain("Next release").doesNotContain("Low exposure");
        assertThat(all).as("Withdrawn and Handled outside the app are left out").doesNotContain("SYS-C6")
                .doesNotContain("SYS-C8");
    }

    /**
     * Decided 2026-10-26: an endorsement after a conditional Exemption lapsed is judged late against the stored
     * {@code endorse_due}, the Cycle's close, so the summary counts and marks the lapses instead, in both tables.
     */
    @Test
    void theSummaryCountsAndMarksTheSystemsWhoseConditionalExemptionLapsed() {
        registered(listed("SYS-L1", "Lapsed", SMO_A, CM, "Q3"), listed("SYS-L2", "Kept", SMO_A, CM, "Q3"));
        ApiClient smo = signedInAs(SMO_A);
        String system = item(inbox(smo), "systems", "SYS-L1").get("id").asString();
        started(smo, "SYS-L1");
        var requested = smo.postJson("/api/systems/" + system + "/exemptions", "{\"basis\":\"SCHEDULED_VA\","
                + "\"vaDate\":\"2027-11-15\",\"evidence\":\"Third-party VA booked with Acme, PO 4471.\","
                + "\"evidenceFileId\":null}");
        assertThat(requested.status()).as(requested.body()).isEqualTo(201);
        String exemption = ApiClient.json(requested).get("id").asString();
        assertThat(vaPoc().postJson("/api/exemptions/" + exemption + "/approve", "{}").status()).isEqualTo(200);
        today("2028-02-01");
        arrange.exemptionLapse();

        XSSFWorkbook workbook = summaryOfTheCycle(vaPoc());

        List<String> q3 = rowStarting(rows(workbook, "Summary"), "Q3");
        List<String> headers = rowStarting(rows(workbook, "Summary"), "Quarter");
        assertThat(q3.get(headers.indexOf("Draft"))).as("back in Draft with new windows").isEqualTo("1");
        assertThat(q3.get(headers.indexOf("Exemption Lapsed"))).isEqualTo("1");
        List<List<String>> systems = rows(workbook, "Systems");
        int lapsed = systems.get(1).indexOf("Exemption Lapsed");
        assertThat(rowStarting(systems, "SYS-L1").get(lapsed)).isEqualTo("Yes");
        assertThat(rowStarting(systems, "SYS-L2").get(lapsed)).isEqualTo("No");
        assertThat(everything(workbook)).as("nothing a person wrote").doesNotContain("Acme").doesNotContain("4471");
    }
}
