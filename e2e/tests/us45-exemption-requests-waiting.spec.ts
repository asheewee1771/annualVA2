import { allure } from "allure-playwright";
import { assertion, expect, flatTexts, test } from "../fixtures/test.ts";
import { requestExemption } from "../fixtures/journeys.ts";
import { lifecycle, shiftDate } from "../fixtures/stack.ts";

const STORY_INTENT =
  "As a VA POC, I want a list of Exemption requests waiting on my dashboard, so that I can decide them promptly.";
const AC_DESCRIPTION =
  "AC1: The VA POC's dashboard has a list of the Exemption requests that are waiting, each with what it rests on and the controls to decide it.";

const EVIDENCE = "Penetration test by the internal red team, report RT-E2E-0042.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("VA POC 1 (after SMO A requests an Exemption)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-002 of the baseline Register (SMO A). SMO A requests an Exemption for it in this scenario, on a VA done 30 days before the Quarter began.",
      "- The Quarter's first day is what the app said when the stack started (the harness's lifecycle record).",
      "- The request is read, not decided: deciding it is another story.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, request an Exemption for E2E-002 with the date of the VA and the evidence.",
      "2. Sign in as VA POC 1 and open the Exemption requests tab.",
      "3. Read the list.",
      "Visible outcomes:",
      "- The list holds one request, for E2E-002, with what it rests on and the evidence SMO A wrote.",
      "- The request has an Approve and a Reject control.",
      "Acceptance mapping:",
      "- AC1 | Story clause: a list of the Exemption requests that are waiting, each with what it rests on | Actor: VA POC 1 | Visible outcome: The list holds one request, for E2E-002, with what it rests on and the evidence SMO A wrote.",
      "- AC1 | Story clause: the controls to decide it | Actor: VA POC 1 | Visible outcome: The request has an Approve and a Reject control.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await requestExemption(page, "E2E-002", shiftDate(lifecycle().baseline.quarterStartsOn, -30), EVIDENCE);

  await signInAs("VA POC 1");
  await page
    .getByRole("navigation", { name: "VA POC views" })
    .getByRole("link", { name: "Exemption requests" })
    .click();
  await expect(page.getByRole("heading", { name: "Exemption requests", level: 2 })).toBeVisible();
  const table = page.getByRole("table", { name: "Exemption requests waiting on a decision" });
  const requests = table.getByRole("row").filter({ hasText: /E2E-\d{3}/ });
  await table.scrollIntoViewIfNeeded();
  const restsOn = "A VA done within the 3 months before the Quarter";
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the waiting Exemption request is listed with what it rests on",
    {
      expected: `one request: E2E-002, ${restsOn}, "${EVIDENCE}"`,
      observed: async () => (await flatTexts(requests)).join(" || "),
    },
    async () => {
      await expect(requests).toHaveCount(1);
      await expect(requests).toContainText("E2E-002");
      await expect(requests).toContainText(restsOn);
      await expect(requests).toContainText(EVIDENCE);
    },
  );

  const approve = requests.getByRole("button", { name: "Approve the Exemption for E2E-002" });
  const reject = requests.getByRole("button", { name: "Reject the Exemption for E2E-002" });
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the request can be decided from the list",
    {
      expected: "Approve enabled, Reject enabled",
      observed: async () =>
        `Approve ${(await approve.isEnabled()) ? "enabled" : "disabled"}, ` +
        `Reject ${(await reject.isEnabled()) ? "enabled" : "disabled"}`,
    },
    async () => {
      await expect(approve).toBeEnabled();
      await expect(reject).toBeEnabled();
    },
  );
});
