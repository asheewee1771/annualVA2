import { allure } from "allure-playwright";
import { assertion, expect, flatText, test } from "../fixtures/test.ts";
import {
  bulkBar,
  findingsTable,
  showAllFindings,
  startSubmission,
  uploadDependencyCheckReport,
} from "../fixtures/journeys.ts";
import { manyFindings } from "../fixtures/scan-reports.ts";
import { lifecycle, shiftDate } from "../fixtures/stack.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want a sticky bulk bar that applies one status, one detail and one target date to every selected Finding, so that I can deal with groups such as all the CVEs in one package in one step.";
const AC_DESCRIPTION =
  "AC1: A bulk bar that stays in view while the Findings list scrolls applies one status, one detail and one target date to every selected Finding in one step.";

const FINDINGS = manyFindings(30);

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A). A generated Dependency-Check report of 30 invented Findings, so that the list is longer than the window.",
      "- One status (Remediation Planned), one detail (the plan) and one target date 30 days after the app's business date.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as SMO A and start the VA Submission of E2E-001.",
      "2. Upload the Dependency-Check report and show all its Findings.",
      "3. Tick the select-all box.",
      "4. Scroll to the last Finding of the list, then back to the first.",
      "5. In the bulk bar choose Remediation Planned, write the plan and the target date, and apply.",
      "Visible outcomes:",
      "- The bulk bar says 30 Findings selected.",
      "- The bulk bar is in the window with the last Finding in view and again with the first Finding in view.",
      "- Each of the 30 Findings shows Remediation Planned, Bulk-set, with the same plan and target date.",
      "Acceptance mapping:",
      "- AC1 | Story clause: every selected Finding | Actor: SMO A | Visible outcome: The bulk bar says 30 Findings selected.",
      "- AC1 | Story clause: stays in view while the Findings list scrolls | Actor: SMO A | Visible outcome: The bulk bar is in the window with the last Finding in view and again with the first Finding in view.",
      "- AC1 | Story clause: applies one status, one detail and one target date | Actor: SMO A | Visible outcome: Each of the 30 Findings shows Remediation Planned, Bulk-set, with the same plan and target date.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await startSubmission(page, "E2E-001");
  await uploadDependencyCheckReport(page, FINDINGS);
  await showAllFindings(page);

  const table = findingsTable(page);
  await table.getByLabel("Select every Finding on this page").check();
  const bar = bulkBar(page);
  const selected = bar.getByText(/Findings? selected/);
  await assertion(
    "Assertion: [AC1] actor: SMO A | the bulk bar takes every selected Finding",
    { expected: "30 Findings selected", observed: () => flatText(selected) },
    async () => {
      await expect(selected).toHaveText("30 Findings selected");
    },
  );

  const first = table.getByRole("checkbox", { name: `Select ${FINDINGS[0]!.identifier}` });
  const last = table.getByRole("checkbox", { name: `Select ${FINDINGS.at(-1)!.identifier}` });
  const inWindow = async (locator: typeof bar) => {
    const box = await locator.boundingBox();
    const height = page.viewportSize()?.height ?? 0;
    return box !== null && box.y < height && box.y + box.height > 0 ? "in the window" : "out of the window";
  };
  // Where the bar was at each end of the list the check scrolled to.
  const positions: string[] = [];
  const scrollToEnd = async (end: "first" | "last", shown: typeof bar, other: typeof bar) => {
    await shown.scrollIntoViewIfNeeded();
    await expect(shown).toBeInViewport();
    await expect(other).not.toBeInViewport();
    positions.push(`${end} Finding in view, the other ${await inWindow(other)}: bar ${await inWindow(bar)}`);
    await expect(bar).toBeInViewport();
  };
  await assertion(
    "Assertion: [AC1] actor: SMO A | the bulk bar stays in view while the list scrolls",
    {
      expected: "bar in the window at both ends of the list",
      observed: async () => positions.join("; ") || "no end of the list reached",
    },
    async () => {
      await scrollToEnd("last", last, first);
      await scrollToEnd("first", first, last);
    },
  );

  const plan = "Upgrade the library in the December release.";
  const targetDate = shiftDate(lifecycle().baseline.businessDate, 30);
  await bar.getByLabel("Status for the selected Findings").selectOption({ label: "Remediation Planned" });
  await bar.getByLabel(/^Plan/).fill(plan);
  await bar.getByLabel(/^Target date/).fill(targetDate);
  await bar.getByRole("button", { name: "Apply to 30" }).click();

  const detail = `${plan} (target date ${targetDate})`;
  const applied = table
    .getByRole("row")
    .filter({ hasText: "Remediation Planned" })
    .filter({ hasText: "Bulk-set" })
    .filter({ hasText: detail });
  await assertion(
    "Assertion: [AC1] actor: SMO A | one status, one detail and one target date are on all 30 Findings",
    {
      expected: `30 rows with Remediation Planned · Bulk-set · ${detail}`,
      observed: async () => `${await applied.count()} such rows; the first: ${await flatText(applied.first())}`,
    },
    async () => {
      await expect(applied).toHaveCount(FINDINGS.length);
      await applied.first().scrollIntoViewIfNeeded();
      for (const finding of [FINDINGS[0]!, FINDINGS.at(-1)!]) {
        await expect(applied.filter({ hasText: finding.identifier })).toHaveCount(1);
      }
    },
  );
});
