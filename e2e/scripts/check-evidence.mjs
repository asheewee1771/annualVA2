#!/usr/bin/env node
// Reads the fresh Allure results of one run and says, for each scenario, how it ended and whether its evidence holds.
// It reads the raw result files, not a report or a summary line. Exit 0 only when nothing is wrong.
//
//   node scripts/check-evidence.mjs <allure-results directory> [--expected <number>] [--stories]
//
// Always: every scenario passed (or is a declared skip) and carries a non-empty video, a non-empty trace and a Test
// plan. --expected: exactly that many scenarios were recorded, so a run that lost some is not taken for a whole one.
// --stories: every scenario binds to a story of e2e/stories.md, word for word: its title, its Allure sub-suite and
// the criterion line of its description are the story's criterion, its feature and suite are the story's, each
// outcome is an `Assertion: [AC1]` step holding an `Evidence: expected: … | observed: …` step, and no story is there twice.
import fs from "node:fs";
import path from "node:path";
import { readStories } from "./lib/stories.mjs";

const [dir, ...flags] = process.argv.slice(2);
const expectedAt = flags.indexOf("--expected");
const expected = expectedAt >= 0 ? Number(flags[expectedAt + 1]) : undefined;
const stories = flags.includes("--stories") ? readStories() : undefined;
if (!dir || !fs.existsSync(dir) || (expectedAt >= 0 && !Number.isInteger(expected))) {
  console.error("Usage: node scripts/check-evidence.mjs <allure-results directory> [--expected <number>] [--stories]");
  process.exit(2);
}

let problems = 0;
function problem(text) {
  problems += 1;
  console.log(`[e2e-evidence]   PROBLEM: ${text}`);
}

/** Every step of a result, at any depth, parents before children. */
function stepsOf(node, found = []) {
  for (const step of node.steps ?? []) {
    found.push(step);
    stepsOf(step, found);
  }
  return found;
}

const label = (result, name) => result.labels?.find((each) => each.name === name)?.value ?? "";

function sizes(result, matches) {
  const attachments = [result, ...stepsOf(result)].flatMap((node) => node.attachments ?? []);
  return attachments.filter(matches).map((attachment) => {
    try {
      return fs.statSync(path.join(dir, attachment.source)).size;
    } catch {
      return 0;
    }
  });
}

function checkEvidence(result) {
  const video = sizes(result, (each) => each.type === "video/webm");
  const trace = sizes(result, (each) => each.name === "trace" || /trace/.test(each.type ?? ""));
  const plan = sizes(result, (each) => each.name === "Test plan");
  const seconds = Math.round(((result.stop ?? 0) - (result.start ?? 0)) / 1000);
  console.log(
    `[e2e-evidence] ${String(result.status).toUpperCase()} ${label(result, "feature")} | ${label(result, "suite")} | ` +
      `${result.name} (${seconds} s, video ${video.join("+") || "none"} bytes, trace ${trace.join("+") || "none"} bytes)`,
  );
  if (result.status === "skipped") return;
  if (result.status !== "passed") problem(`ended ${result.status}`);
  if (!video.some((size) => size > 0)) problem("no non-empty video");
  if (!trace.some((size) => size > 0)) problem("no non-empty trace");
  if (!plan.some((size) => size > 0)) problem("no Test plan attachment");
}

/** Holds one result to the story its suite names; returns the story's id, or undefined when it names none. */
function checkStory(result) {
  const story = stories.get(/^(US\d+):/.exec(label(result, "suite"))?.[1] ?? "");
  if (!story) {
    problem(`its suite "${label(result, "suite")}" names no story of stories.md`);
    return undefined;
  }
  const same = (what, actual, wanted) => {
    if (actual !== wanted) problem(`${story.id}: ${what} is "${actual}", but stories.md says "${wanted}"`);
  };
  same("the suite", label(result, "suite"), story.suiteLabel);
  same("the feature", label(result, "feature"), story.featureLabel);
  same("the title", result.name, story.criterion);
  same("the sub-suite", label(result, "subSuite"), story.criterion);
  const paragraphs = String(result.description ?? "")
    .trim()
    .split(/\n\s*\n/);
  // A scenario that did not pass ends its description with one more paragraph, the reason; the criterion comes before it.
  const reasoned = /^(Failure|Blocked): /.test(paragraphs.at(-1) ?? "");
  same("the criterion line of the description", (paragraphs.at(reasoned ? -2 : -1) ?? "").trim(), story.criterion);

  const assertions = stepsOf(result).filter((step) => step.name.startsWith("Assertion: "));
  if (result.status !== "skipped" && assertions.length === 0) problem(`${story.id}: no Assertion step`);
  for (const assertion of assertions) {
    if (!assertion.name.startsWith("Assertion: [AC1] "))
      problem(`${story.id}: "${assertion.name}" does not name [AC1]`);
    const evidence = (assertion.steps ?? []).filter((step) => /^Evidence: expected: .* \| observed: /s.test(step.name));
    if (evidence.length !== 1)
      problem(`${story.id}: "${assertion.name}" holds ${evidence.length} Evidence steps, not 1`);
  }
  return story.id;
}

const results = fs
  .readdirSync(dir)
  .filter((name) => name.endsWith("-result.json"))
  .map((name) => JSON.parse(fs.readFileSync(path.join(dir, name), "utf8")))
  .sort((a, b) => (a.start ?? 0) - (b.start ?? 0));

const seen = [];
for (const result of results) {
  checkEvidence(result);
  if (stories) seen.push(checkStory(result));
}
if (stories) {
  for (const id of new Set(seen.filter(Boolean))) {
    const times = seen.filter((each) => each === id).length;
    if (times > 1) problem(`${id} was recorded ${times} times`);
  }
}
if (results.length === 0) problem("no scenario was recorded");
if (expected !== undefined && results.length !== expected) {
  const missing = stories ? [...stories.keys()].filter((id) => !seen.includes(id)) : [];
  problem(
    `${results.length} scenarios recorded, ${expected} expected${missing.length > 0 ? `; missing: ${missing.join(", ")}` : ""}`,
  );
}
console.log(`[e2e-evidence] ${results.length} scenarios, ${problems} problems`);
process.exit(problems === 0 ? 0 : 1);
