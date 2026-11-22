import { allure } from "allure-playwright";
import { assertion, expect, flatText, flatTexts, test } from "../fixtures/test.ts";
import {
  otherDisputed,
  reachReworkQueue,
  reworkFinding,
  reworkFindingIdentifier,
  reworkPosition,
} from "../fixtures/journeys.ts";
import { TWO_FINDINGS } from "../fixtures/scan-reports.ts";

const STORY_INTENT =
  "As an SMO/Tech Lead, I want keyboard shortcuts in the rework queue (1–4 picks a status, Enter saves and moves on), so that rework is fast.";
const AC_DESCRIPTION =
  "AC1: In the rework queue a key from 1 to 4 picks a status, and Enter saves it and moves on to the next Finding.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("SMO A (after SMO A submits and Cyber Manager A returns)");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- System E2E-001 of the baseline Register (SMO A, Cyber Manager A). A generated Dependency-Check report of two invented Findings, both Disputed by Cyber Manager A.",
      "- The queue offers four statuses on the keys 1 to 4; key 4 is Remediation Planned and key 1 is Remediated. Only the keyboard is used once the queue is open.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. As SMO A, submit E2E-001; as Cyber Manager A, Dispute both Findings and return it.",
      "2. As SMO A, open the rework queue.",
      "3. Press 4, then Esc to leave the text box it opens, then press 1.",
      "4. Type the evidence, press Esc to leave the text box, then press Enter.",
      "Visible outcomes:",
      "- After 4, Remediation Planned is the picked status; after 1, Remediated is, and its Evidence box is open.",
      "- After Enter, the queue shows the other Finding and the position reads Finding 1 of 1.",
      "Acceptance mapping:",
      "- AC1 | Story clause: a key from 1 to 4 picks a status | Actor: SMO A | Visible outcome: After 4, Remediation Planned is the picked status; after 1, Remediated is, and its Evidence box is open.",
      "- AC1 | Story clause: Enter saves it and moves on to the next Finding | Actor: SMO A | Visible outcome: After Enter, the queue shows the other Finding and the position reads Finding 1 of 1.",
    ].join("\n"),
    "text/plain",
  );

  await reachReworkQueue(page, signInAs, TWO_FINDINGS);
  const card = reworkFinding(page);
  const first = await reworkFindingIdentifier(page);
  const statuses = card.getByRole("group", { name: "Pick a status" });
  await expect(statuses.getByRole("button")).toHaveCount(4);
  const picked = statuses.getByRole("button", { pressed: true });
  const pickedNow = async () => (await flatTexts(picked)).join(", ") || "none";

  await page.keyboard.press("4");
  // The text box the status opens takes the keys; Esc leaves it so that the next key is a shortcut again.
  const plan = card.getByLabel(/^Plan/);
  await assertion(
    "Assertion: [AC1] actor: SMO A | the key 4 picks Remediation Planned",
    { expected: "4 Remediation Planned, with its Plan box open", observed: pickedNow },
    async () => {
      await expect(picked).toHaveText("4 Remediation Planned");
      await expect(plan).toBeFocused();
    },
  );
  await page.keyboard.press("Escape");
  await expect(plan).not.toBeFocused();

  await page.keyboard.press("1");
  const evidence = card.getByLabel(/^Evidence/);
  await assertion(
    "Assertion: [AC1] actor: SMO A | the key 1 picks Remediated",
    { expected: "1 Remediated, with its Evidence box open", observed: pickedNow },
    async () => {
      await expect(picked).toHaveText("1 Remediated");
      await expect(evidence).toBeFocused();
    },
  );

  await page.keyboard.type("Upgraded to the fixed version in the hotfix of this week.");
  await page.keyboard.press("Escape");
  await expect(evidence).not.toBeFocused();
  await page.keyboard.press("Enter");

  const other = otherDisputed(first).identifier;
  const position = reworkPosition(page);
  await assertion(
    "Assertion: [AC1] actor: SMO A | Enter saves the status and the queue moves on",
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
