import { allure } from "allure-playwright";
import { assertion, expect, flatText, test, type Persona } from "../fixtures/test.ts";
import { resetStack } from "../fixtures/stack.ts";
import { startSubmission } from "../fixtures/journeys.ts";

// The foundation smoke test: sign-in through the persona picker for each of the six personas, navigation to each
// role's home, and the harness reset, proved by changing something and seeing it gone. It is not a story scenario.

const HOME: Readonly<Record<Persona, string>> = {
  "SMO A": "Your Systems",
  "SMO B": "Your Systems",
  "Cyber Manager A": "Your Systems to review",
  "Cyber Manager B": "Your Systems to review",
  "VA POC 1": "Quarter board",
  "VA POC 2": "Quarter board",
};

const TITLE = "Foundation: the six personas sign in, each lands on its role's home, and a reset puts the baseline back";

test(TITLE, async ({ page, signInAs, cleanStart }) => {
  await allure.feature("Feature: E2E foundation");
  await allure.suite("Foundation smoke");
  await allure.description(TITLE);
  await allure.parameter("persona", "all six dev personas");
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline System Register the harness loads at start: E2E-001 and E2E-002 (SMO A, Cyber Manager A), E2E-003 (SMO B, Cyber Manager B).",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset (node scripts/stack.mjs reset) before the test and once inside it.",
      "Steps:",
      "1. Sign in as each of the six personas through the persona picker and read the home each lands on.",
      "2. As SMO A, start the VA Submission of E2E-001.",
      "3. Run the harness reset.",
      "4. Sign in as SMO A again and read the state of E2E-001.",
      "Visible outcomes:",
      "- Each persona's name is in the header and its role's home is shown.",
      "- After the reset E2E-001 is Not Started again and can be started.",
    ].join("\n"),
    "text/plain",
  );

  await assertion(
    "Assertion: the reset before the test ran through the harness command",
    { expected: "reset done", observed: cleanStart.trim() },
    async () => {
      expect(cleanStart).toContain("reset done");
    },
  );

  const banner = page.getByRole("banner");
  for (const persona of Object.keys(HOME) as Persona[]) {
    await signInAs(persona);
    const home = page.getByRole("heading", { name: HOME[persona], level: 2, exact: true });
    await assertion(
      `Assertion: ${persona} signs in through the picker and lands on "${HOME[persona]}"`,
      {
        expected: `${persona} / ${HOME[persona]}`,
        observed: async () => `${await flatText(banner)} / ${await flatText(home)}`,
      },
      async () => {
        await expect(banner).toContainText(persona);
        await expect(home).toBeVisible();
      },
    );
  }

  await signInAs("SMO A");
  await startSubmission(page, "E2E-001");
  await page.getByRole("link", { name: "Back to your Systems" }).click();
  const row = page.getByRole("row").filter({ hasText: "E2E-001" });
  await expect(row).toContainText("Draft");

  const resetOutput = await resetStack();
  // The reset ended every sign-in, this browser's included: start again from the signed-out app.
  await page.context().clearCookies();
  await page.goto("/");
  await signInAs("SMO A");
  await assertion(
    "Assertion: after the harness reset E2E-001 is Not Started again",
    { expected: "Not Started, with Start", observed: async () => `${await flatText(row)} (${resetOutput.trim()})` },
    async () => {
      await expect(row).toContainText("Not Started");
      await expect(page.getByRole("button", { name: "Start the VA Submission for E2E-001" })).toBeEnabled();
    },
  );
});
