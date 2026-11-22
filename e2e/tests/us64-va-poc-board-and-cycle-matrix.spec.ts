import { allure } from "allure-playwright";
import { assertion, expect, flatText, flatTexts, test } from "../fixtures/test.ts";

const STORY_INTENT =
  "As a VA POC, I want a board for the current Quarter and a matrix of the whole VA Cycle (a strip showing all four Quarters, then one row per System with its milestone dots against their due weeks), as two tabs, so that I see both today's work and the year.";
const AC_DESCRIPTION =
  "AC1: The VA POC has two tabs: a board of the current Quarter, and a Cycle matrix with a strip of all four Quarters and one row per System with its milestones and when each is due.";

const SYSTEMS = ["E2E-001", "E2E-002", "E2E-003"];

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("VA POC 1");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline Register: E2E-001, E2E-002 and E2E-003, all in the Quarter the app is running and none started.",
      "- The matrix shows each milestone as words with its due date, which is how this build draws the prototype's dots.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as VA POC 1 and read the tabs and the board the app lands on.",
      "2. Open the Cycle matrix tab.",
      "3. Read the strip of Quarters and the table of Systems.",
      "Visible outcomes:",
      "- The first two tabs are Quarter board and Cycle matrix, and the board shows the three Systems under Not Started.",
      "- The strip shows Q1, Q2, Q3 and Q4.",
      "- The table has one row for each of the three Systems, each with three milestones and a due date for each.",
      "Acceptance mapping:",
      "- AC1 | Story clause: two tabs: a board of the current Quarter | Actor: VA POC 1 | Visible outcome: The first two tabs are Quarter board and Cycle matrix, and the board shows the three Systems under Not Started.",
      "- AC1 | Story clause: a strip of all four Quarters | Actor: VA POC 1 | Visible outcome: The strip shows Q1, Q2, Q3 and Q4.",
      "- AC1 | Story clause: one row per System with its milestones and when each is due | Actor: VA POC 1 | Visible outcome: The table has one row for each of the three Systems, each with three milestones and a due date for each.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("VA POC 1");
  const tabs = page.getByRole("navigation", { name: "VA POC views" }).getByRole("link");
  await expect(page.getByRole("heading", { name: "Quarter board", level: 2 })).toBeVisible();
  const notStarted = page
    .getByRole("region", { name: "Quarter board columns" })
    .getByRole("region", { name: /^Not Started/ });
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the first of two tabs is the board of the current Quarter",
    {
      expected: "tabs Quarter board, Cycle matrix; Not Started (3) with the three Systems",
      observed: async () => `tabs ${(await flatTexts(tabs)).slice(0, 2).join(", ")}; ${await flatText(notStarted)}`,
    },
    async () => {
      await expect(tabs.nth(0)).toHaveText("Quarter board");
      await expect(tabs.nth(1)).toHaveText("Cycle matrix");
      await expect(tabs.nth(0)).toHaveAttribute("aria-current", "page");
      await expect(notStarted.getByRole("heading")).toHaveText("Not Started (3)");
      for (const system of SYSTEMS) await expect(notStarted).toContainText(system);
    },
  );

  await tabs.nth(1).click();
  await expect(page.getByRole("heading", { name: "Cycle matrix", level: 2 })).toBeVisible();
  const quarters = page
    .getByRole("list", { name: "Quarters of the VA Cycle" })
    .getByRole("listitem")
    .filter({ hasText: /^Q[1-4] \d{4}/ });
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the Cycle matrix has a strip of all four Quarters",
    { expected: "Q1, Q2, Q3, Q4", observed: async () => (await flatTexts(quarters)).join(" || ") },
    async () => {
      await expect(quarters).toHaveText([/^Q1 /, /^Q2 /, /^Q3 /, /^Q4 /]);
    },
  );

  const table = page.getByRole("table", { name: "Systems of the VA Cycle and their milestones" });
  const rows = table.getByRole("row").filter({ hasText: /E2E-\d{3}/ });
  await table.scrollIntoViewIfNeeded();
  const threeDueDates = /(?:[Dd]ue \d{1,2} \w{3,4} \d{4}.*){3}/s;
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the matrix has one row per System with its three milestones and their due dates",
    {
      expected: `one row each for ${SYSTEMS.join(", ")}, three due dates in each`,
      observed: async () => (await flatTexts(rows)).join(" || "),
    },
    async () => {
      await expect(rows).toHaveCount(SYSTEMS.length);
      for (const system of SYSTEMS) {
        const row = rows.filter({ hasText: system });
        await expect(row).toHaveCount(1);
        await expect(row).toHaveText(threeDueDates);
      }
      await expect(table.getByRole("columnheader")).toContainText([/^Submitted/, /^Reviewed/, /^Endorsed/]);
    },
  );
  await test.info().attach("Cycle matrix table", { body: await table.screenshot(), contentType: "image/png" });
});
