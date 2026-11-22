#!/usr/bin/env node
// One evidence run from a clean start: start the stack, run a Playwright project into an evidence folder of its own,
// build the Allure report, check the evidence, stop the stack.
//
//   node scripts/run.mjs smoke        the foundation smoke test (foundation-tests/)
//   node scripts/run.mjs acceptance   the acceptance scenarios (tests/)
//
// Anything after the project name goes to Playwright (a spec file, --grep ...). E2E_CAMPAIGN_LABEL adds a label of
// your own (a ticket, a story) to the run's id. E2E_KEEP_STACK=1 leaves the stack up afterwards; a healthy stack that
// is already running is used as it is and left running.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import {
  ARTIFACTS_ROOT,
  HARNESS_ROOT,
  LIFECYCLE_FILE,
  LOCAL_BROWSERS_DIR,
  TEMP_DIR,
  TEMP_ENVIRONMENT,
  readLifecycle,
  stamp,
} from "./lib/config.mjs";
import { readStories } from "./lib/stories.mjs";

const [project, ...extra] = process.argv.slice(2);
if (project !== "smoke" && project !== "acceptance") {
  console.error("Usage: node scripts/run.mjs smoke|acceptance [playwright arguments]");
  process.exit(2);
}
const label = process.env.E2E_CAMPAIGN_LABEL ?? "";
if (!/^[A-Za-z0-9-]*$/.test(label)) {
  console.error("E2E_CAMPAIGN_LABEL may hold letters, digits and hyphens only: it becomes part of a folder name.");
  process.exit(2);
}

const node = process.execPath;
const script = (name) => path.join(HARNESS_ROOT, "scripts", name);
const bin = (...parts) => path.join(HARNESS_ROOT, "node_modules", ...parts);

function step(what, command, args, env = process.env) {
  console.log(`[e2e-run] ${what}`);
  return spawnSync(command, args, { cwd: HARNESS_ROOT, stdio: "inherit", env }).status ?? 1;
}

const stack = (command) => step(`stack ${command}`, node, [script("stack.mjs"), command]);

const campaignId = [stamp(), label, project].filter(Boolean).join("-");
const campaign = path.join(ARTIFACTS_ROOT, "campaigns", campaignId);
fs.mkdirSync(campaign, { recursive: true });

// A healthy stack is used as it is. A recorded one that is not healthy is stopped first, which is safe: `stop` only
// stops processes that are still the ones it started.
const reused = fs.existsSync(LIFECYCLE_FILE) && stack("status") === 0;
if (!reused) {
  if (fs.existsSync(LIFECYCLE_FILE)) stack("stop");
  if (stack("start") !== 0) process.exit(1);
}

let exit = 1;
try {
  const lifecycle = readLifecycle();
  fs.mkdirSync(TEMP_DIR, { recursive: true });
  const results = path.join(campaign, "allure-results");
  const env = {
    ...process.env,
    ...TEMP_ENVIRONMENT,
    E2E_CAMPAIGN_SUITE: campaignId,
    E2E_RETRIES: process.env.E2E_RETRIES ?? "0",
    ALLURE_RESULTS_DIR: results,
    PLAYWRIGHT_OUTPUT_DIR: path.join(campaign, "test-results"),
  };
  // Browsers installed inside the checkout are used when they are there and nothing else was asked for.
  if (!env.PLAYWRIGHT_BROWSERS_PATH && fs.existsSync(LOCAL_BROWSERS_DIR)) {
    env.PLAYWRIGHT_BROWSERS_PATH = LOCAL_BROWSERS_DIR;
  }

  const startedAt = new Date().toISOString();
  // The test runner is started by its own path: the Playwright that @playwright/test pins, and no other.
  const playwright = [bin("@playwright", "test", "cli.js"), "test", "--project", project, ...extra];
  const tests = step(`playwright test --project ${project}`, node, playwright, env);
  const awesome = ["awesome", results, "--output", path.join(campaign, "allure-report")];
  const report = step(
    "allure awesome",
    node,
    [bin("allure", "cli.js"), ...awesome, "--group-by", "feature,suite,subSuite"],
    env,
  );

  // The whole set must be there: a run that recorded fewer scenarios than it should have is not evidence. With
  // arguments of your own (one spec, a --grep) the number is yours to judge, and only the scenarios recorded are checked.
  const check = [script("check-evidence.mjs"), results];
  if (project === "acceptance") check.push("--stories");
  if (extra.length === 0) check.push("--expected", String(project === "acceptance" ? readStories().size : 1));
  const evidence = step("check the evidence", node, check, env);

  const record = {
    campaignId,
    project,
    mode: lifecycle.mode,
    lifecycle: lifecycle.id,
    baseUrl: lifecycle.baseUrl,
    startedAt,
    finishedAt: new Date().toISOString(),
    exit: { tests, report, evidence },
  };
  fs.writeFileSync(path.join(campaign, "run.json"), `${JSON.stringify(record, null, 2)}\n`);
  exit = tests || report || evidence;
  console.log(`[e2e-run] ${campaignId}: tests=${tests} report=${report} evidence=${evidence}`);
  console.log(`[e2e-run] results and report: ${campaign}`);
} finally {
  if (!reused && process.env.E2E_KEEP_STACK !== "1") stack("stop");
}
process.exit(exit);
