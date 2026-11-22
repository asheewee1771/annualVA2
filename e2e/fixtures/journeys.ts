import { expect, test, type Locator, type Page } from "@playwright/test";
import { readWorkbook } from "../scripts/lib/xlsx.mjs";
import { dependencyCheckReport, FIVE_FINDINGS, type FixtureFinding } from "./scan-reports.ts";

/**
 * Journeys several scenarios share to reach their starting state, each one done in the browser the way the persona
 * does it: by the roles, labels and words the screens show. They set a scenario up; a scenario still proves its own
 * outcome itself. Nothing here calls the API or the database.
 */

const BY_HAND = "Enter the Findings by hand: the tool cannot export a machine-readable report";
const NOT_APPLICABLE = "Not applicable to this System";

// The cards of the screen where a VA Submission is prepared.

export function findingsCard(page: Page): Locator {
  return page.getByRole("region", { name: "Findings", exact: true });
}

export function findingsTable(page: Page): Locator {
  return findingsCard(page).getByRole("table", { name: "Findings of this VA Submission" });
}

/** The sentence under the progress bar: how many Findings have a proposed status, are still to do, are Disputed. */
export function progressSummary(page: Page): Locator {
  return findingsCard(page).getByText(/Findings have a proposed status/);
}

export function declarationCard(page: Page): Locator {
  return page.getByRole("region", { name: "Declaration", exact: true });
}

/** The bulk bar: shown while at least one Finding is ticked. */
export function bulkBar(page: Page): Locator {
  return page.getByRole("form", { name: "Apply one status to the selected Findings" });
}

/** SMO/Tech Lead, on their inbox: starts the VA Submission of one of their Systems and lands on its prepare screen. */
export async function startSubmission(page: Page, systemId: string): Promise<void> {
  await page.getByRole("button", { name: `Start the VA Submission for ${systemId}` }).click();
  await expect(page.getByRole("heading", { name: "Prepare the VA Submission" })).toBeVisible();
}

/**
 * SMO/Tech Lead, on the prepare screen: uploads the Dependency-Check report and waits until the local scanner has
 * passed it and its Findings are listed.
 */
export async function uploadDependencyCheckReport(
  page: Page,
  findings: readonly FixtureFinding[] = FIVE_FINDINGS,
): Promise<void> {
  const card = page.getByRole("region", { name: "OWASP Dependency-Check" });
  await card.getByLabel(/^Add files/).setInputFiles(dependencyCheckReport(findings));
  await card.getByRole("button", { name: "Upload", exact: true }).click();
  await expect(card.getByText("Accepted, findings read")).toBeVisible({ timeout: 60_000 });
  await expect(findingsTable(page)).toBeVisible();
  await expect(progressSummary(page)).toContainText(`0 of ${findings.length} Findings have a proposed status`);
}

/** SMO/Tech Lead, on the prepare screen: shows every Finding, whatever its status. */
export async function showAllFindings(page: Page): Promise<void> {
  const all = findingsCard(page).getByRole("button", { name: "All", exact: true });
  await all.click();
  await expect(all).toHaveAttribute("aria-pressed", "true");
}

/** The identifiers of the Findings the list shows now, top to bottom, as the report named them. */
export async function shownFindingIdentifiers(page: Page): Promise<string[]> {
  const rows = await findingsTable(page).getByRole("row").allInnerTexts();
  const identifiers = rows.map((row) => /CVE-\d{4}-\d+/.exec(row)?.[0]).filter((found) => found !== undefined);
  return [...new Set(identifiers)];
}

/**
 * SMO/Tech Lead, on the prepare screen: says how a Scan Report with no file is provided (its Findings entered by hand,
 * or not applicable) and waits until it no longer stands in the way of submitting.
 */
export async function provideWithoutFile(
  page: Page,
  report: "Secure Configuration" | "Burp Suite",
  how: "by hand" | "not applicable",
  why: string,
): Promise<void> {
  const card = page.getByRole("region", { name: report });
  const byHand = how === "by hand";
  await card.getByLabel(/^How this Scan Report is provided/).selectOption({ label: byHand ? BY_HAND : NOT_APPLICABLE });
  await card.getByLabel(byHand ? /^Why the tool's report cannot be uploaded/ : /^Why it does not apply/).fill(why);
  await card.getByRole("button", { name: `Save how ${report} is provided` }).click();
  await expect(declarationCard(page).getByText(new RegExp(`^The ${report} Scan Report`))).toBeHidden();
}

/** SMO/Tech Lead, on the prepare screen: provides the two Scan Reports that have no file here, each its own way. */
export async function provideTheOtherScanReports(page: Page): Promise<void> {
  const noExport = "The hardening tool on this host has no machine-readable export.";
  await provideWithoutFile(page, "Secure Configuration", "by hand", noExport);
  await provideWithoutFile(page, "Burp Suite", "not applicable", "The System has no web interface.");
}

/** SMO/Tech Lead, on the prepare screen with every Finding shown: proposes False Positive for one Finding on its row. */
export async function proposeFalsePositive(
  page: Page,
  identifier: string,
  justification: string,
  proposedAfterwards: number,
): Promise<void> {
  const table = findingsTable(page);
  await table.getByLabel(`Propose a status for ${identifier}`).selectOption({ label: "False Positive" });
  await table.getByLabel(/^Justification/).fill(justification);
  await table.getByRole("button", { name: `Propose False Positive for ${identifier}` }).click();
  const progress = new RegExp(`^${proposedAfterwards} of \\d+ Findings have a proposed status`);
  await expect(progressSummary(page)).toContainText(progress);
}

/**
 * SMO/Tech Lead, on the prepare screen with every Finding shown: ticks them all and proposes Remediated for them in one
 * step; waits until the progress says every Finding has a proposed status.
 */
export async function proposeRemediatedForAll(page: Page, count: number): Promise<void> {
  await findingsTable(page).getByLabel("Select every Finding on this page").check();
  const bar = bulkBar(page);
  await bar.getByLabel("Status for the selected Findings").selectOption({ label: "Remediated" });
  await bar
    .getByLabel(/^Evidence/)
    .fill("Upgraded in the release of last week; the rescan is attached to the change record.");
  await bar.getByRole("button", { name: `Apply to ${count}` }).click();
  await expect(progressSummary(page)).toContainText(`${count} of ${count} Findings have a proposed status`);
}

/** SMO/Tech Lead, on the prepare screen: ticks the Declaration and submits; waits until the state reads In Review. */
export async function submitForReview(page: Page): Promise<void> {
  const card = declarationCard(page);
  await card.getByLabel(/^I accept the Declaration/).check();
  await card.getByRole("button", { name: "Submit for review" }).click();
  await expect(page.getByText(/· In Review/)).toBeVisible();
}

/**
 * SMO/Tech Lead, from their inbox: a whole first submission of one System. The Dependency-Check report is uploaded,
 * the other two Scan Reports are provided without a file, every Finding is proposed False Positive on its own row, and
 * the VA Submission is submitted for review.
 */
export async function submitWithEveryFindingProposed(
  page: Page,
  systemId: string,
  findings: readonly FixtureFinding[],
): Promise<void> {
  await startSubmission(page, systemId);
  await uploadDependencyCheckReport(page, findings);
  await provideTheOtherScanReports(page);
  await showAllFindings(page);
  for (const [index, finding] of findings.entries()) {
    const justification = `Not reachable in this deployment: ${finding.identifier}.`;
    await proposeFalsePositive(page, finding.identifier, justification, index + 1);
  }
  await submitForReview(page);
}

export interface Dispute {
  identifier: string;
  comment: string;
}

/** The two Findings of the two-Finding report, each Disputed with a comment of its own. */
export const DISPUTES: readonly Dispute[] = [
  {
    identifier: "CVE-2099-10001",
    comment: "The justification does not say why the vulnerable class cannot be reached.",
  },
  { identifier: "CVE-2099-20001", comment: "Name the vendor advisory that says this version is not affected." },
];

/** Of the two Disputed Findings, the one that is not this one. */
export function otherDisputed(identifier: string): Dispute {
  const other = DISPUTES.find((dispute) => dispute.identifier !== identifier);
  if (!other) throw new Error(`No Disputed Finding other than ${identifier}`);
  return other;
}

/**
 * Cyber Manager, from their inbox: opens the review of a System's VA Submission, Disputes the named Findings with a
 * comment each, and returns the VA Submission for rework.
 */
export async function disputeAndReturn(page: Page, systemId: string, disputes: readonly Dispute[]): Promise<void> {
  await page.getByRole("link", { name: `Review ${systemId}`, exact: true }).click();
  await expect(page.getByRole("heading", { name: "Review VA Submission" })).toBeVisible();
  for (const { identifier, comment } of disputes) {
    await page.getByLabel(new RegExp(`^Comment on ${identifier} `)).fill(comment);
    await page.getByRole("button", { name: `Dispute ${identifier}`, exact: true }).click();
    // The row then shows the latest review with the comment as text.
    await expect(page.getByRole("paragraph").filter({ hasText: comment })).toBeVisible();
  }
  const giveBack = page.getByRole("button", { name: "Return for rework" });
  await expect(giveBack).toBeEnabled();
  await giveBack.click();
  // Once it is returned nothing is left to review here, so the step that finishes a review is gone.
  await expect(giveBack).toBeHidden();
}

/** SMO/Tech Lead, from their inbox: opens the rework queue of a System whose VA Submission was Returned for Rework. */
export async function openReworkQueue(page: Page, systemId: string): Promise<void> {
  await page.getByRole("link", { name: `Continue the VA Submission for ${systemId}` }).click();
  await expect(page.getByRole("heading", { name: "Rework the VA Submission" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Rework queue" })).toBeVisible();
}

/**
 * The whole way to a rework queue with Disputed Findings in it: SMO A submits E2E-001 with a status proposed for every
 * Finding, Cyber Manager A Disputes the named Findings and returns it, and SMO A opens the queue. Three personas act, one
 * after the other, each signed in through the persona picker.
 */
export async function reachReworkQueue(
  page: Page,
  signInAs: (persona: "SMO A" | "Cyber Manager A") => Promise<void>,
  findings: readonly FixtureFinding[],
  disputes: readonly Dispute[] = DISPUTES,
): Promise<void> {
  await signInAs("SMO A");
  await submitWithEveryFindingProposed(page, "E2E-001", findings);
  await signInAs("Cyber Manager A");
  await disputeAndReturn(page, "E2E-001", disputes);
  await signInAs("SMO A");
  await openReworkQueue(page, "E2E-001");
}

/** The card of the rework queue that shows the one Finding being worked on. */
export function reworkFinding(page: Page): Locator {
  return page.getByRole("region", { name: "This Finding" });
}

/** The card of the rework queue with its stages, the keys and the position in the queue. */
export function reworkQueue(page: Page): Locator {
  return page.getByRole("region", { name: "Rework queue" });
}

/** Where the SMO/Tech Lead is in the queue: "Finding 1 of 2", with Previous and Skip beside it. */
export function reworkPosition(page: Page): Locator {
  return reworkQueue(page).getByText(/^Finding \d+ of \d+/);
}

/** The identifier of the one Finding the rework queue shows now. */
export async function reworkFindingIdentifier(page: Page): Promise<string> {
  const card = reworkFinding(page);
  await expect(card).toContainText(/CVE-\d{4}-\d+/);
  const found = new Set((await card.innerText()).match(/CVE-\d{4}-\d+/g) ?? []);
  if (found.size !== 1)
    throw new Error(`The rework queue shows ${found.size} Findings at once: ${[...found].join(", ")}`);
  return [...found][0]!;
}

/** SMO/Tech Lead, from their inbox: asks for an Exemption for a System on a VA done before the Quarter. */
export async function requestExemption(page: Page, systemId: string, vaDate: string, evidence: string): Promise<void> {
  await page.getByRole("link", { name: `Request Exemption for ${systemId}` }).click();
  await expect(page.getByRole("heading", { name: "Request an Exemption" })).toBeVisible();
  await page.getByLabel(/^Date of the VA/).fill(vaDate);
  await page.getByLabel(/^Evidence: who did the VA/).fill(evidence);
  await page.getByRole("button", { name: "Request the Exemption" }).click();
  await expect(page.getByRole("heading", { name: "Your Systems" })).toBeVisible();
}

/**
 * VA POC, from the VA POC area: asks for the management summary as the Exports page offers it (Excel, the whole VA
 * Cycle, every System) and waits until it is ready. Returns its row in the list of exports.
 */
export async function askForManagementSummary(page: Page): Promise<Locator> {
  await page.getByRole("navigation", { name: "VA POC views" }).getByRole("link", { name: "Exports" }).click();
  await expect(page.getByRole("heading", { name: "Exports", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Ask for the summary" }).click();
  const row = page
    .getByRole("table", { name: /^Your exports/ })
    .getByRole("row")
    .filter({ hasText: "Excel: VA Cycle" });
  await expect(row).toHaveCount(1);
  // It is made in the background; the page asks again by itself until it is ready.
  await expect(row.getByRole("button", { name: "Download" })).toBeVisible({ timeout: 90_000 });
  return row;
}

/** VA POC, on the Exports page: downloads a ready export, keeps it with the test's output, and opens it. */
export async function downloadWorkbook(page: Page, row: Locator) {
  const downloading = page.waitForEvent("download");
  await row.getByRole("button", { name: "Download" }).click();
  const download = await downloading;
  const file = test.info().outputPath(download.suggestedFilename());
  await download.saveAs(file);
  return { fileName: download.suggestedFilename(), sheets: readWorkbook(file).sheets };
}
