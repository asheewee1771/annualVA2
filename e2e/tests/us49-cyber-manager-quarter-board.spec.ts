import { allure } from "allure-playwright";
import { assertion, expect, flatText, flatTexts, test } from "../fixtures/test.ts";

const STORY_INTENT =
  "As a Cyber Manager, I want a Quarter board as a second tab, with one column per state, so that I can see the whole Quarter at once.";
const AC_DESCRIPTION =
  "AC1: The Cyber Manager's second tab is a Quarter board that shows their Systems in one column per state.";

/** The states a VA Submission passes through on its way to endorsement, as the glossary (CONTEXT.md) names them. */
const WORKFLOW_STATES = [
  "Not Started",
  "Draft",
  "In Review",
  "Returned for Rework",
  "Awaiting Endorsement",
  "Endorsed",
];

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("Cyber Manager A");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline Register: E2E-001 and E2E-002 name Cyber Manager A; neither has a VA Submission yet, so both are Not Started.",
      "- The states a column is expected for are the workflow states of CONTEXT.md: Not Started, Draft, In Review, Returned for Rework, Awaiting Endorsement, Endorsed.",
      "- Who may see which System is an access rule and stays at Seam 1: only Cyber Manager A's own Systems are looked for.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as Cyber Manager A.",
      "2. Read the tabs of the Cyber Manager's area.",
      "3. Open the second tab and read the board's columns.",
      "Visible outcomes:",
      "- The second tab is Quarter board and it opens a Quarter board.",
      "- The board has one column for each workflow state, none twice, and Not Started holds E2E-001 and E2E-002.",
      "Acceptance mapping:",
      "- AC1 | Story clause: second tab is a Quarter board | Actor: Cyber Manager A | Visible outcome: The second tab is Quarter board and it opens a Quarter board.",
      "- AC1 | Story clause: their Systems in one column per state | Actor: Cyber Manager A | Visible outcome: The board has one column for each workflow state, none twice, and Not Started holds E2E-001 and E2E-002.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("Cyber Manager A");
  const tabs = page.getByRole("navigation", { name: "Cyber Manager views" }).getByRole("link");
  await tabs.nth(1).click();
  const heading = page.getByRole("heading", { name: "Quarter board", level: 2 });
  await assertion(
    "Assertion: [AC1] actor: Cyber Manager A | the second tab opens the Quarter board",
    { expected: 'second tab "Quarter board"', observed: async () => `tabs ${(await flatTexts(tabs)).join(", ")}` },
    async () => {
      await expect(tabs.nth(1)).toHaveText("Quarter board");
      await expect(tabs.nth(1)).toHaveAttribute("aria-current", "page");
      await expect(heading).toBeVisible();
    },
  );

  const board = page.getByRole("region", { name: "Quarter board columns" });
  const columns = board.getByRole("heading", { level: 3 });
  const notStarted = board.getByRole("region", { name: /^Not Started/ });
  await board.scrollIntoViewIfNeeded();
  const states = async () => (await flatTexts(columns)).map((column) => column.replace(/\s*\(\d+\)$/, ""));
  await assertion(
    "Assertion: [AC1] actor: Cyber Manager A | the board has one column per state with the Systems in them",
    {
      expected: `a column each for ${WORKFLOW_STATES.join(", ")}; Not Started (2) with E2E-001 and E2E-002`,
      observed: async () => `columns ${(await flatTexts(columns)).join(", ")}; ${await flatText(notStarted)}`,
    },
    async () => {
      await expect(columns).toHaveText(Array.from({ length: await columns.count() }, () => /^.+ \(\d+\)$/));
      await expect.poll(async () => new Set(await states()).size).toBe(await columns.count());
      await expect.poll(states).toEqual(expect.arrayContaining(WORKFLOW_STATES));
      await expect(notStarted.getByRole("heading")).toHaveText("Not Started (2)");
      await expect(notStarted).toContainText("E2E-001");
      await expect(notStarted).toContainText("E2E-002");
    },
  );
});
