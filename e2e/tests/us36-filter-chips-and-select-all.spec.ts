import { allure } from "allure-playwright";
import { assertion, expect, flatText, test } from "../fixtures/test.ts";
import {
  bulkBar,
  findingsCard,
  findingsTable,
  shownFindingIdentifiers,
  startSubmission,
  uploadDependencyCheckReport,
} from "../fixtures/journeys.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want a spreadsheet-style list with filter chips (Needs attention, All, Carried Over, Target Date Missed, Disputed, Critical/High), tick boxes and select-all, so that I can work through 100–200 Findings.";
const AC_DESCRIPTION =
  "AC1: The Findings list offers the filter chips Needs attention, All, Disputed and Critical/High, a tick box on every Finding, and a select-all that ticks every Finding the chosen filter shows.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A). A generated Dependency-Check report of five invented Findings: CVE-2099-10001 Critical, CVE-2099-10002 High, three Medium or Low.",
      "- Release 1 scope (ticket 20): the Carried Over and Target Date Missed chips arrive in Release 2.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as SMO A and start the VA Submission of E2E-001.",
      "2. Upload the Dependency-Check report and wait for its Findings.",
      "3. Read the filter chips, then choose Critical/High.",
      "4. Tick the select-all box.",
      "Visible outcomes:",
      "- The chips are Needs attention, All, Disputed and Critical/High.",
      "- Critical/High shows only the Critical and the High Finding, each with a tick box.",
      "- Select-all ticks both and the bulk bar says 2 Findings selected.",
      "Acceptance mapping:",
      "- AC1 | Story clause: filter chips Needs attention, All, Disputed and Critical/High | Actor: SMO A | Visible outcome: The chips are Needs attention, All, Disputed and Critical/High.",
      "- AC1 | Story clause: a tick box on every Finding | Actor: SMO A | Visible outcome: Critical/High shows only the Critical and the High Finding, each with a tick box.",
      "- AC1 | Story clause: a select-all that ticks every Finding the chosen filter shows | Actor: SMO A | Visible outcome: Select-all ticks both and the bulk bar says 2 Findings selected.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await startSubmission(page, "E2E-001");
  await uploadDependencyCheckReport(page);

  const chips = findingsCard(page).getByRole("group", { name: "Show Findings" }).getByRole("button");
  const expectedChips = ["Needs attention", "All", "Disputed", "Critical/High"];
  await assertion(
    "Assertion: [AC1] actor: SMO A | the list offers the four filter chips of Release 1",
    { expected: expectedChips.join(", "), observed: async () => (await chips.allInnerTexts()).join(", ") },
    async () => {
      await expect(chips).toHaveText(expectedChips);
    },
  );

  await findingsCard(page).getByRole("button", { name: "Critical/High" }).click();
  const expectedShown = ["CVE-2099-10001", "CVE-2099-10002"];
  const table = findingsTable(page);
  await table.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | Critical/High shows only those Findings, each with a tick box",
    { expected: expectedShown.join(", "), observed: async () => (await shownFindingIdentifiers(page)).join(", ") },
    async () => {
      await expect.poll(() => shownFindingIdentifiers(page)).toEqual(expectedShown);
      for (const identifier of expectedShown) await expect(table.getByLabel(`Select ${identifier}`)).not.toBeChecked();
    },
  );

  await table.getByLabel("Select every Finding on this page").check();
  const selected = bulkBar(page).getByText(/Findings? selected/);
  await assertion(
    "Assertion: [AC1] actor: SMO A | select-all ticks every Finding the filter shows",
    { expected: "2 Findings selected", observed: () => flatText(selected) },
    async () => {
      for (const identifier of expectedShown) await expect(table.getByLabel(`Select ${identifier}`)).toBeChecked();
      await expect(selected).toHaveText("2 Findings selected");
    },
  );
});
