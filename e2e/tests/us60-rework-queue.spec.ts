import { allure } from "allure-playwright";
import { assertion, expect, flatText, flatTexts, test } from "../fixtures/test.ts";
import {
  DISPUTES,
  otherDisputed,
  reachReworkQueue,
  reworkFinding,
  reworkFindingIdentifier,
  reworkPosition,
  reworkQueue,
} from "../fixtures/journeys.ts";
import { TWO_FINDINGS } from "../fixtures/scan-reports.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead whose submission was Returned for Rework, I want a guided queue that takes me one Finding at a time (Disputed, then Target Date Missed, then Carried Over to re-confirm, then new Critical/High, then new Medium/Low), so that I fix exactly what was raised.";
const AC_DESCRIPTION =
  "AC1: A VA Submission Returned for Rework opens as a guided queue that shows one Finding at a time, the Disputed Findings first, and moves to the next when one is saved.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A (after SMO A submits and Cyber Manager A returns)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A, Cyber Manager A). A generated Dependency-Check report of two invented Findings, CVE-2099-10001 and CVE-2099-20001.",
      "- Cyber Manager A Disputes both Findings, each with its own comment, and returns the VA Submission.",
      "- Release 1 scope (ticket 31): the Target Date Missed and Carried Over stages arrive in Release 2. Which Disputed Finding comes first is the server's order and is proved at Seam 1, not here.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, submit E2E-001 with a status proposed for both Findings.",
      "2. As Cyber Manager A, Dispute both Findings and return the VA Submission for rework.",
      "3. As SMO A, open the rework queue from the inbox and read it.",
      "4. Pick False Positive, rewrite the justification and save.",
      "5. Read the queue again.",
      "Visible outcomes:",
      "- The queue's first stage is Disputed with 2 left, and the position reads Finding 1 of 2.",
      "- One Disputed Finding is shown, not both.",
      "- After saving, the queue shows the other Disputed Finding and the position reads Finding 1 of 1.",
      "Acceptance mapping:",
      "- AC1 | Story clause: the Disputed Findings first | Actor: SMO A | Visible outcome: The queue's first stage is Disputed with 2 left, and the position reads Finding 1 of 2.",
      "- AC1 | Story clause: shows one Finding at a time | Actor: SMO A | Visible outcome: One Disputed Finding is shown, not both.",
      "- AC1 | Story clause: moves to the next when one is saved | Actor: SMO A | Visible outcome: After saving, the queue shows the other Disputed Finding and the position reads Finding 1 of 1.",
    ].join("\n"),
    "text/plain",
  );

  await reachReworkQueue(page, signInAs, TWO_FINDINGS);

  const stages = reworkQueue(page).getByRole("list", { name: "Stages of the rework queue" }).getByRole("listitem");
  const position = reworkPosition(page);
  await assertion(
    "Assertion: [AC1] actor: SMO A | the queue starts with the Disputed Findings",
    {
      expected: 'first stage "Disputed: 2 left", position "Finding 1 of 2"',
      observed: async () => `stages ${(await flatTexts(stages)).join(", ")}; position ${await flatText(position)}`,
    },
    async () => {
      await expect(stages.first()).toHaveText("Disputed: 2 left");
      await expect(stages.first()).toHaveAttribute("aria-current", "step");
      await expect(position).toContainText("Finding 1 of 2");
    },
  );

  const card = reworkFinding(page);
  const disputed = DISPUTES.map((dispute) => dispute.identifier);
  // Which Disputed Finding comes first is the server's order (Seam 1); the scenario goes on from whichever it shows.
  let first = "";
  await card.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: SMO A | one Disputed Finding is shown at a time",
    { expected: `exactly one of ${disputed.join(", ")}`, observed: () => reworkFindingIdentifier(page) },
    async () => {
      // It fails when the card shows more than one Finding.
      first = await reworkFindingIdentifier(page);
      expect(disputed).toContain(first);
      await expect(card.getByText("Disputed", { exact: true })).toBeVisible();
    },
  );

  await card.getByRole("button", { name: "2 False Positive", exact: true }).click();
  const rewritten = "The class is excluded from the build: see the dependency exclusions in the build file.";
  await card.getByLabel(/^Justification/).fill(rewritten);
  await card.getByRole("button", { name: "Save False Positive and go to the next" }).click();

  const other = otherDisputed(first).identifier;
  await assertion(
    "Assertion: [AC1] actor: SMO A | saving moves the queue to the next Disputed Finding",
    {
      expected: `${other}, position "Finding 1 of 1"`,
      observed: async () => `${await reworkFindingIdentifier(page)}, position ${await flatText(position)}`,
    },
    async () => {
      await expect(card).toContainText(other);
      await expect.poll(() => reworkFindingIdentifier(page)).toBe(other);
      await expect(position).toContainText("Finding 1 of 1");
    },
  );
});
