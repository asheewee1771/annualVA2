import { allure } from "allure-playwright";
import { assertion, expect, flatText, flatTexts, test } from "../fixtures/test.ts";
import { submitWithEveryFindingProposed } from "../fixtures/journeys.ts";
import { TWO_FINDINGS } from "../fixtures/scan-reports.ts";

const STORY_INTENT =
  "As a Cyber Manager, I want an action inbox of VA Submissions for the Systems assigned to me in the System Register, with Overdue ones first, so that I work in the right order.";
const AC_DESCRIPTION =
  "AC1: The Cyber Manager's inbox lists the Systems the System Register assigns to them and puts a VA Submission that was submitted under Needs your review.";

const OWN_SYSTEMS = ["E2E-001", "E2E-002"];

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("Cyber Manager A (after SMO A submits)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline Register: E2E-001 and E2E-002 name Cyber Manager A.",
      "- SMO A submits E2E-001 in this scenario; E2E-002 is left Not Started.",
      "- Overdue first is an ordering rule that needs a clock the dev-local profile does not have, and who may see which System is an access rule; both stay at Seam 1 and are not read here.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, submit the VA Submission of E2E-001 for review.",
      "2. Sign in as Cyber Manager A.",
      "3. Read the inbox the app lands on.",
      "Visible outcomes:",
      "- The inbox lists E2E-001 and E2E-002.",
      "- Needs your review holds E2E-001, In Review, with a Review link.",
      "Acceptance mapping:",
      "- AC1 | Story clause: lists the Systems the System Register assigns to them | Actor: Cyber Manager A | Visible outcome: The inbox lists E2E-001 and E2E-002.",
      "- AC1 | Story clause: puts a VA Submission that was submitted under Needs your review | Actor: Cyber Manager A | Visible outcome: Needs your review holds E2E-001, In Review, with a Review link.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("SMO A");
  await submitWithEveryFindingProposed(page, "E2E-001", TWO_FINDINGS);

  await signInAs("Cyber Manager A");
  await expect(page.getByRole("heading", { name: "Your Systems to review" })).toBeVisible();
  const inbox = page.getByRole("main");
  const rows = OWN_SYSTEMS.map((systemId) => inbox.getByRole("row").filter({ hasText: systemId }));
  const listed = inbox.getByRole("row").filter({ hasText: /E2E-\d{3}/ });
  await assertion(
    "Assertion: [AC1] actor: Cyber Manager A | the inbox lists the Systems assigned to Cyber Manager A",
    { expected: OWN_SYSTEMS.join(", "), observed: async () => (await flatTexts(listed)).join(" || ") },
    async () => {
      for (const row of rows) await expect(row).toBeVisible();
    },
  );

  const needsReview = page.getByRole("region", { name: /^Needs your review/ });
  const submitted = needsReview.getByRole("row").filter({ hasText: "E2E-001" });
  await needsReview.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: Cyber Manager A | the submitted VA Submission is under Needs your review",
    {
      expected: "Needs your review (1): E2E-001, In Review, Review link",
      observed: async () => `${await flatText(needsReview.getByRole("heading"))}: ${await flatText(submitted)}`,
    },
    async () => {
      await expect(needsReview.getByRole("heading")).toHaveText("Needs your review (1)");
      await expect(submitted).toContainText("In Review");
      await expect(submitted.getByRole("link", { name: "Review E2E-001", exact: true })).toBeVisible();
    },
  );
});
