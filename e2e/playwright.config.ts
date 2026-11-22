import path from "node:path";
import { defineConfig, devices } from "@playwright/test";
import { ARTIFACTS_ROOT, BASE_URL } from "./scripts/lib/config.mjs";

// Seam 2: a thin browser layer against the app under dev-local. Evidence capture is decided here and nowhere else:
// every test records a video and a trace, whoever runs it. An evidence run (scripts/run.mjs) names its own result
// folders through the environment; a run without one writes to a scratch folder that is overwritten.
const scratch = path.join(ARTIFACTS_ROOT, "scratch");
const resultsDir = process.env.ALLURE_RESULTS_DIR ?? path.join(scratch, "allure-results");
const outputDir = process.env.PLAYWRIGHT_OUTPUT_DIR ?? path.join(scratch, "test-results");

export default defineConfig({
  globalSetup: "./fixtures/global-setup.ts",
  // One scenario at a time: each one starts from a reset of the one stack.
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  retries: Number(process.env.E2E_RETRIES ?? 0),
  timeout: 180_000,
  expect: { timeout: 15_000 },
  outputDir,
  reporter: [["list"], ["allure-playwright", { resultsDir, detail: true, suiteTitle: false }]],
  use: {
    ...devices["Desktop Chrome"],
    baseURL: BASE_URL,
    // The dev certificates are self-signed and the stack is on this machine only.
    ignoreHTTPSErrors: true,
    video: "on",
    trace: "on",
    screenshot: "only-on-failure",
    locale: "en-SG",
    timezoneId: "Asia/Singapore",
  },
  projects: [
    { name: "smoke", testDir: "./foundation-tests" },
    { name: "acceptance", testDir: "./tests" },
  ],
});
