import { allure } from "allure-playwright";
import { assertion, expect, flatText, test } from "../fixtures/test.ts";
import {
  DISPUTES,
  otherDisputed,
  reachReworkQueue,
  reworkFinding,
  reworkFindingIdentifier,
  reworkQueue,
} from "../fixtures/journeys.ts";
import { TWO_FINDINGS } from "../fixtures/scan-reports.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want to see the Cyber Manager's Dispute comment on each Finding, so that I know what to change.";
const AC_DESCRIPTION =
  "AC1: The rework queue shows, on each Disputed Finding, the comment the Cyber Manager wrote when Disputing it.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A (after SMO A submits and Cyber Manager A returns)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A, Cyber Manager A). A generated Dependency-Check report of two invented Findings.",
      "- Cyber Manager A Disputes CVE-2099-10001 and CVE-2099-20001, each with a different comment, typed in this scenario.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, submit E2E-001 with a status proposed for both Findings.",
      "2. As Cyber Manager A, Dispute both Findings with a comment each and return the VA Submission for rework.",
      "3. As SMO A, open the rework queue and read the Finding it shows.",
      "4. Skip to the next Finding and read it.",
      "Visible outcomes:",
      "- The first Finding shown carries the comment written for it, marked as Disputed by the Cyber Manager.",
      "- The next Finding carries its own comment, not the first one's.",
      "Acceptance mapping:",
      "- AC1 | Story clause: the comment the Cyber Manager wrote when Disputing it | Actor: SMO A | Visible outcome: The first Finding shown carries the comment written for it, marked as Disputed by the Cyber Manager.",
      "- AC1 | Story clause: on each Disputed Finding | Actor: SMO A | Visible outcome: The next Finding carries its own comment, not the first one's.",
    ].join("\n"),
    "text/plain",
  );

  await reachReworkQueue(page, signInAs, TWO_FINDINGS);
  const card = reworkFinding(page);

  const first = await reworkFindingIdentifier(page);
  const firstComment = DISPUTES.find((dispute) => dispute.identifier === first)!.comment;
  await card.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | the Finding shows the Cyber Manager's comment on it",
    { expected: `${first}: Disputed by the Cyber Manager, "${firstComment}"`, observed: () => flatText(card) },
    async () => {
      await expect(card.getByText(/^Disputed by the Cyber Manager on /)).toBeVisible();
      await expect(card.getByText(firstComment, { exact: true })).toBeVisible();
    },
  );

  await reworkQueue(page).getByRole("button", { name: "Skip" }).click();
  const other = otherDisputed(first);
  await card.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | the next Finding shows its own comment",
    { expected: `${other.identifier}: "${other.comment}"`, observed: () => flatText(card) },
    async () => {
      await expect(card).toContainText(other.identifier);
      await expect.poll(() => reworkFindingIdentifier(page)).toBe(other.identifier);
      await expect(card.getByText(other.comment, { exact: true })).toBeVisible();
      await expect(card.getByText(firstComment, { exact: true })).toBeHidden();
    },
  );
});
