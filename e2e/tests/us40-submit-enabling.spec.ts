import { allure } from "allure-playwright";
import { assertion, expect, test } from "../fixtures/test.ts";
import {
  declarationCard,
  proposeRemediatedForAll,
  provideTheOtherScanReports,
  showAllFindings,
  startSubmission,
  uploadDependencyCheckReport,
} from "../fixtures/journeys.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want Submit to stay disabled until every Finding has a proposed status and I've ticked the fixed Declaration text, so that I can't submit something incomplete.";
const AC_DESCRIPTION =
  "AC1: Submit stays disabled until every Finding has a proposed status and the Declaration is ticked.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A). A generated Dependency-Check report of five invented Findings.",
      "- The other two Scan Reports are provided without a file, so that only the Findings and the Declaration are left to do.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as SMO A, start the VA Submission of E2E-001 and upload the Dependency-Check report.",
      "2. Provide Secure Configuration by hand and mark Burp Suite not applicable.",
      "3. Tick the Declaration while no Finding has a status, read Submit, then untick it.",
      "4. Propose a status for all five Findings in one step and read Submit.",
      "5. Tick the Declaration and read Submit.",
      "Visible outcomes:",
      "- With the Declaration ticked and no status proposed, Submit for review is disabled.",
      "- With every status proposed and the Declaration not ticked, Submit for review is disabled.",
      "- With every status proposed and the Declaration ticked, Submit for review is enabled.",
      "Acceptance mapping:",
      "- AC1 | Story clause: until every Finding has a proposed status | Actor: SMO A | Visible outcome: With the Declaration ticked and no status proposed, Submit for review is disabled.",
      "- AC1 | Story clause: and the Declaration is ticked | Actor: SMO A | Visible outcome: With every status proposed and the Declaration not ticked, Submit for review is disabled.",
      "- AC1 | Story clause: Submit stays disabled until | Actor: SMO A | Visible outcome: With every status proposed and the Declaration ticked, Submit for review is enabled.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await startSubmission(page, "E2E-001");
  await uploadDependencyCheckReport(page);
  await provideTheOtherScanReports(page);

  const card = declarationCard(page);
  const accept = card.getByLabel(/^I accept the Declaration/);
  const submit = card.getByRole("button", { name: "Submit for review" });
  const unproposed = card.getByText(/^Every Finding needs a proposed status/);
  const state = async () =>
    `Declaration ${(await accept.isChecked()) ? "ticked" : "not ticked"}, ` +
    `Submit ${(await submit.isDisabled()) ? "disabled" : "enabled"}`;

  await accept.check();
  await submit.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | Submit is disabled while Findings have no proposed status",
    { expected: "Declaration ticked, Submit disabled", observed: state },
    async () => {
      await expect(accept).toBeChecked();
      await expect(unproposed).toBeVisible();
      await expect(submit).toBeDisabled();
    },
  );
  await accept.uncheck();

  await showAllFindings(page);
  await proposeRemediatedForAll(page, 5);
  await submit.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | Submit is disabled while the Declaration is not ticked",
    { expected: "Declaration not ticked, Submit disabled", observed: state },
    async () => {
      await expect(accept).not.toBeChecked();
      await expect(unproposed).toBeHidden();
      await expect(submit).toBeDisabled();
    },
  );

  await accept.check();
  await assertion(
    "Assertion: [AC1] actor: SMO A | Submit is enabled once every status is proposed and the Declaration ticked",
    { expected: "Declaration ticked, Submit enabled", observed: state },
    async () => {
      await expect(accept).toBeChecked();
      await expect(submit).toBeEnabled();
    },
  );
});
