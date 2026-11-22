import { allure } from "allure-playwright";
import { assertion, expect, flatText, test } from "../fixtures/test.ts";
import {
  DISPUTES,
  disputeAndReturn,
  findingsCard,
  openReworkQueue,
  progressSummary,
  proposeFalsePositive,
  provideTheOtherScanReports,
  showAllFindings,
  startSubmission,
  submitForReview,
  uploadDependencyCheckReport,
} from "../fixtures/journeys.ts";
import { TWO_FINDINGS } from "../fixtures/scan-reports.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want a progress bar showing how many Findings have a proposed status, with counts of Carried Over, Target Date Missed and Disputed, so that I know how much is left.";
const AC_DESCRIPTION =
  "AC1: A progress bar shows how many Findings have a proposed status, how many are still to do and how many are Disputed, and moves when a status is proposed or a Finding is Disputed.";

const [FIRST, SECOND] = TWO_FINDINGS.map((finding) => finding.identifier) as [string, string];
const DISPUTE = DISPUTES[0]!;

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A (Cyber Manager A Disputes one Finding in between)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A, Cyber Manager A). A generated Dependency-Check report of two invented Findings.",
      "- Cyber Manager A Disputes one of the two Findings and returns the VA Submission, so that SMO A's progress has a Disputed Finding to count.",
      "- Release 1 scope (ticket 20): the Carried Over and Target Date Missed counts arrive in Release 2; they are not read here.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, start the VA Submission of E2E-001, upload the report and read the progress bar.",
      "2. Propose a status for one Finding and read the progress bar again.",
      "3. Propose a status for the other, provide the other Scan Reports and submit for review.",
      "4. As Cyber Manager A, Dispute one Finding and return the VA Submission.",
      "5. As SMO A, open the whole VA Submission and read the progress bar.",
      "Visible outcomes:",
      "- Before any proposal the progress reads 0 of 2, 2 still to do, 0 Disputed.",
      "- After one proposal the progress reads 1 of 2, 1 still to do.",
      "- After the Cyber Manager's Dispute the progress reads 1 Disputed.",
      "Acceptance mapping:",
      "- AC1 | Story clause: shows how many Findings have a proposed status, how many are still to do and how many are Disputed | Actor: SMO A | Visible outcome: Before any proposal the progress reads 0 of 2, 2 still to do, 0 Disputed.",
      "- AC1 | Story clause: moves when a status is proposed | Actor: SMO A | Visible outcome: After one proposal the progress reads 1 of 2, 1 still to do.",
      "- AC1 | Story clause: or a Finding is Disputed | Actor: SMO A | Visible outcome: After the Cyber Manager's Dispute the progress reads 1 Disputed.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await startSubmission(page, "E2E-001");
  await uploadDependencyCheckReport(page, TWO_FINDINGS);

  const bar = findingsCard(page).getByRole("progressbar", { name: "Findings with a proposed status" });
  const summary = progressSummary(page);
  const reading = async () =>
    `${await flatText(summary)} (bar ${await bar.getAttribute("value")} of ${await bar.getAttribute("max")})`;

  await summary.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | the progress shows none of the two Findings proposed and none Disputed",
    {
      expected: "0 of 2 Findings have a proposed status; 2 still to do. … 0 Disputed. (bar 0 of 2)",
      observed: reading,
    },
    async () => {
      await expect(summary).toContainText("0 of 2 Findings have a proposed status; 2 still to do.");
      await expect(summary).toContainText("0 Disputed");
      await expect(bar).toHaveAttribute("value", "0");
      await expect(bar).toHaveAttribute("max", "2");
    },
  );

  await showAllFindings(page);
  await proposeFalsePositive(page, FIRST, `Not reachable in this deployment: ${FIRST}.`, 1);
  await summary.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | the progress moves to one of two after a status is proposed",
    { expected: "1 of 2 Findings have a proposed status; 1 still to do. (bar 1 of 2)", observed: reading },
    async () => {
      await expect(summary).toContainText("1 of 2 Findings have a proposed status; 1 still to do.");
      await expect(bar).toHaveAttribute("value", "1");
    },
  );

  await proposeFalsePositive(page, SECOND, `Not reachable in this deployment: ${SECOND}.`, 2);
  await provideTheOtherScanReports(page);
  await submitForReview(page);
  await signInAs("Cyber Manager A");
  await disputeAndReturn(page, "E2E-001", [DISPUTE]);
  await signInAs("SMO A");
  await openReworkQueue(page, "E2E-001");
  await page.getByRole("link", { name: "The whole VA Submission" }).click();
  await expect(page.getByRole("heading", { name: "Prepare the VA Submission" })).toBeVisible();

  await summary.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | the progress counts the Finding the Cyber Manager Disputed",
    { expected: "1 Disputed", observed: reading },
    async () => {
      await expect(summary).toContainText("1 Disputed");
    },
  );
});
