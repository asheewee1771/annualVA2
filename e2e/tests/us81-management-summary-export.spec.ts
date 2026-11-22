import { allure } from "allure-playwright";
import { assertion, expect, flatText, test } from "../fixtures/test.ts";
import {
  askForManagementSummary,
  downloadWorkbook,
  startSubmission,
  uploadDependencyCheckReport,
} from "../fixtures/journeys.ts";
import { FIVE_FINDINGS } from "../fixtures/scan-reports.ts";
import { lifecycle } from "../fixtures/stack.ts";

const STORY_INTENT =
  "As a VA POC, I want a management summary export (counts by Quarter and state, Endorsed Late, and a by-System table with SMO, state, Overdue and the number of open Deviations) in Excel or PDF, with no vulnerability details, so that it's safe to circulate.";
const AC_DESCRIPTION =
  "AC1: A VA POC asks for the management summary, downloads it as an Excel workbook that lists the Systems, and the workbook holds no vulnerability details.";

const SYSTEMS = ["E2E-001", "E2E-002", "E2E-003"];

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("VA POC 1 (after SMO A uploads a Scan Report)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline Register of three Systems. SMO A starts E2E-001 and uploads a generated Dependency-Check report, so the app holds five invented Findings (CVE-2099-…) in packages named e2e-alpha and e2e-beta.",
      "- The export is asked for as the page offers it: an Excel workbook, the whole VA Cycle, every System.",
      "- What the summary counts and how it is laid out is proved at Seam 1; here the downloaded file is opened and read.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, start E2E-001 and upload the Dependency-Check report.",
      "2. Sign in as VA POC 1, open Exports and ask for the summary.",
      "3. Wait until it is Ready and download it.",
      "4. Open the workbook and read its cells.",
      "Visible outcomes:",
      "- The export is listed as an Excel file covering the VA Cycle and 3 Systems, and it downloads as an .xlsx file.",
      "- The workbook names E2E-001, E2E-002 and E2E-003.",
      "- No cell of the workbook holds a Finding's identifier or package.",
      "Acceptance mapping:",
      "- AC1 | Story clause: asks for the management summary, downloads it as an Excel workbook | Actor: VA POC 1 | Visible outcome: The export is listed as an Excel file covering the VA Cycle and 3 Systems, and it downloads as an .xlsx file.",
      "- AC1 | Story clause: that lists the Systems | Actor: VA POC 1 | Visible outcome: The workbook names E2E-001, E2E-002 and E2E-003.",
      "- AC1 | Story clause: the workbook holds no vulnerability details | Actor: VA POC 1 | Visible outcome: No cell of the workbook holds a Finding's identifier or package.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await startSubmission(page, "E2E-001");
  await uploadDependencyCheckReport(page);

  await signInAs("VA POC 1");
  const row = await askForManagementSummary(page);
  const workbook = await downloadWorkbook(page, row);
  const cells = workbook.sheets.flatMap((sheet) => sheet.cells);
  await allure.attachment("Words of the downloaded workbook", cells.join("\n"), "text/plain");

  const covers = `Excel: VA Cycle ${lifecycle().baseline.cycle}, 3 Systems`;
  await row.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the summary is made and downloads as an Excel workbook",
    {
      expected: `${covers}, Ready, a file ending .xlsx`,
      observed: async () => `${await flatText(row)}; file ${workbook.fileName}`,
    },
    async () => {
      await expect(row).toContainText(covers);
      await expect(row).toContainText("Ready");
      expect(workbook.fileName).toMatch(/\.xlsx$/);
    },
  );

  const named = SYSTEMS.filter((system) => cells.some((cell) => cell.includes(system)));
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the workbook lists the Systems",
    { expected: SYSTEMS.join(", "), observed: `${named.join(", ") || "none"} (${cells.length} cells of text)` },
    async () => {
      expect(named).toEqual(SYSTEMS);
    },
  );

  const detail = [
    ...FIVE_FINDINGS.flatMap((finding) => [finding.identifier, finding.packageId]),
    "CVE-",
    "e2e-alpha",
    "e2e-beta",
  ];
  const leaked = cells.filter((cell) => detail.some((word) => cell.includes(word)));
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the workbook holds no vulnerability details",
    {
      expected: "no cell names a Finding or its package",
      observed: leaked.length === 0 ? "none of them appears" : leaked.join(" || "),
    },
    async () => {
      expect(leaked).toEqual([]);
    },
  );
});
