import { allure } from "allure-playwright";
import { assertion, expect, flatTexts, test } from "../fixtures/test.ts";
import { lifecycle } from "../fixtures/stack.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want to see my Systems for the current Quarter, with what I need to do and by when, so that I know where to start.";
const AC_DESCRIPTION =
  "AC1: The SMO/Tech Lead's inbox lists their own Systems of the current Quarter, each with what to do next and the date it is due.";

const OWN_SYSTEMS = ["E2E-001", "E2E-002"];

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline Register: E2E-001 and E2E-002 name SMO A, in the Quarter the app is running, which the harness read from the app when the stack started.",
      "- Who may see which System is an access rule and stays at Seam 1: only SMO A's own Systems are looked for.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as SMO A.",
      "2. Read the inbox the app lands on.",
      "3. Read the row of each System.",
      "Visible outcomes:",
      "- The inbox lists E2E-001 and E2E-002.",
      "- Each row names the current Quarter and reads Start the VA Submission by a date.",
      "Acceptance mapping:",
      "- AC1 | Story clause: lists their own Systems of the current Quarter | Actor: SMO A | Visible outcome: The inbox lists E2E-001 and E2E-002.",
      "- AC1 | Story clause: each with what to do next and the date it is due | Actor: SMO A | Visible outcome: Each row names the current Quarter and reads Start the VA Submission by a date.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await expect(page.getByRole("heading", { name: "Your Systems" })).toBeVisible();
  const table = page.getByRole("table", { name: "Needs your action" });
  const rows = OWN_SYSTEMS.map((systemId) => table.getByRole("row").filter({ hasText: systemId }));
  const listed = table.getByRole("row").filter({ hasText: /E2E-\d{3}/ });
  await assertion(
    "Assertion: [AC1] actor: SMO A | the inbox lists SMO A's own Systems",
    { expected: OWN_SYSTEMS.join(", "), observed: async () => (await flatTexts(listed)).join(" || ") },
    async () => {
      for (const row of rows) await expect(row).toBeVisible();
    },
  );

  const quarter = `Q${lifecycle().baseline.quarter}`;
  const next = /^Start the VA Submission by \d{1,2} \w{3,4} \d{4}$/;
  await assertion(
    "Assertion: [AC1] actor: SMO A | each System shows what to do next and by when",
    {
      expected: `${quarter} | Not Started | Start the VA Submission by <date>`,
      observed: async () => (await flatTexts(listed)).join(" || "),
    },
    async () => {
      for (const row of rows) {
        await expect(row.getByRole("cell", { name: quarter, exact: true })).toBeVisible();
        await expect(row.getByRole("cell", { name: next })).toBeVisible();
      }
    },
  );
});
