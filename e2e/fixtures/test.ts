import path from "node:path";
import * as allure from "allure-js-commons";
import { test as base, expect, type Locator, type Page } from "@playwright/test";
import { HARNESS_ROOT, PERSONAS } from "../scripts/lib/config.mjs";
import { storyWithCriterion } from "../scripts/lib/stories.mjs";
import { lifecycle, resetStack } from "./stack.ts";

export type Persona = keyof typeof PERSONAS;

interface Fixtures {
  /** Every scenario starts from the baseline: the harness resets the stack before the test's first step. */
  cleanStart: string;
  /** What every result carries, set once here: the run it belongs to, its criterion as sub-suite, and the stack. */
  evidenceLabels: void;
  /** Signs in as a dev persona through the persona picker; signs the previous persona out first. */
  signInAs: (persona: Persona) => Promise<void>;
  /**
   * Binds the scenario to the story of stories.md whose AC1 is the test's title, word for word: the Allure feature and
   * suite come from the table. A test whose title is no story's criterion, or whose file is not the one the table names,
   * is stopped before it does anything.
   */
  story: (persona: string) => Promise<void>;
}

async function signOutIfSignedIn(page: Page): Promise<void> {
  const signOut = page.getByRole("button", { name: "Sign out" });
  if (await signOut.isVisible()) {
    await signOut.click();
    await expect(signOut).toBeHidden();
  }
}

export const test = base.extend<Fixtures>({
  cleanStart: [
    async ({}, use) => {
      await use(await resetStack());
    },
    { auto: true },
  ],

  evidenceLabels: [
    async ({}, use, testInfo) => {
      await allure.parentSuite(process.env.E2E_CAMPAIGN_SUITE ?? "local");
      await allure.subSuite(testInfo.title);
      await allure.parameter(
        "e2e_configuration",
        `isolated E2E stack (dev-local, lifecycle ${lifecycle().id}, reset before the scenario)`,
      );
      await use();
    },
    { auto: true },
  ],

  signInAs: async ({ page }, use) => {
    await use(async (persona) => {
      await signOutIfSignedIn(page);
      await page.goto("/dev-sign-in");
      // The picker says what it is on every visit: a local development sign-in, not the single sign-on.
      await expect(page.getByRole("note").filter({ hasText: "Local development sign-in." })).toBeVisible();
      await page.getByRole("button", { name: new RegExp(`^${persona}\\b`) }).click();
      await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
    });
  },

  story: async ({}, use, testInfo) => {
    await use(async (persona) => {
      const bound = storyWithCriterion(testInfo.title);
      const specFile = path.relative(HARNESS_ROOT, testInfo.file).split(path.sep).join("/");
      if (specFile !== bound.specFile) {
        throw new Error(`stories.md names ${bound.specFile} as the spec file of ${bound.id}, not ${specFile}`);
      }
      await allure.feature(bound.featureLabel);
      await allure.suite(bound.suiteLabel);
      await allure.parameter("persona", persona);
    });
  },
});

/** What a locator shows, on one line: the text a reader sees, without the layout's line breaks. */
export async function flatText(locator: Locator): Promise<string> {
  return (await locator.innerText()).replace(/\s+/g, " ").trim();
}

/** The same for each element a locator finds. */
export async function flatTexts(locator: Locator): Promise<string[]> {
  return (await locator.allInnerTexts()).map((text) => text.replace(/\s+/g, " ").trim());
}

/** How long the evidence waits for `observed` once the check is over, passed or failed. */
const OBSERVED_READ_MS = 5_000;

/**
 * One outcome of a scenario, in the shape the evidence check looks for: an `Assertion:` step holding one
 * `Evidence: expected: … | observed: …` step. The matchers of `check` run inside that step; `observed` is read when
 * they are done, or have failed, so that it records what the screen showed when the outcome was judged.
 */
export async function assertion(
  title: `Assertion: ${string}`,
  evidence: { expected: string; observed: string | (() => Promise<string>) },
  check: () => Promise<void>,
): Promise<void> {
  const evidenceStep = (observed: string) => `Evidence: expected: ${evidence.expected} | observed: ${observed}`;
  await allure.step(title, async () => {
    await allure.step(evidenceStep("(read after the check)"), async (step) => {
      try {
        await check();
      } finally {
        await step.displayName(evidenceStep(await readObserved(evidence.observed)));
      }
    });
  });
}

/** Reads `observed` without letting a failed screen hold the test up: a read that fails or hangs is recorded as such. */
async function readObserved(observed: string | (() => Promise<string>)): Promise<string> {
  if (typeof observed === "string") return observed;
  const read = observed();
  // A read still running when the time is up must not fail the test later.
  read.catch(() => undefined);
  let timer: NodeJS.Timeout | undefined;
  const late = new Promise<string>((resolve) => {
    timer = setTimeout(() => resolve(`not readable within ${OBSERVED_READ_MS / 1000} s`), OBSERVED_READ_MS);
  });
  try {
    return await Promise.race([read, late]);
  } catch (error) {
    return `not readable: ${(error instanceof Error ? error.message : String(error)).split(/\r?\n/)[0]}`;
  } finally {
    clearTimeout(timer);
  }
}

export { expect };
