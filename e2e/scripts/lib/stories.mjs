// Reads the table of e2e/stories.md: the one place that says which story a scenario binds to, under which feature, and
// its criterion word for word. The fixtures label a scenario from it and the evidence check holds every result to it.
import fs from "node:fs";
import path from "node:path";
import { HARNESS_ROOT } from "./config.mjs";

export const STORIES_FILE = path.join(HARNESS_ROOT, "stories.md");

/**
 * @typedef {object} Story
 * @property {string} id for example "US36"
 * @property {string} title the story's short title
 * @property {string} feature the feature it belongs to
 * @property {string} criterion its one criterion, starting "AC1: "
 * @property {string} featureLabel the Allure feature, "Feature: <feature>"
 * @property {string} suiteLabel the Allure suite, "US36: <title>"
 * @property {string} specFile its spec file, relative to e2e/, for example "tests/us36-filter-chips-and-select-all.spec.ts"
 */

/** @returns {Map<string, Story>} the stories by id, in the table's order */
export function readStories(file = STORIES_FILE) {
  const stories = new Map();
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const cells = line.split("|").map((cell) => cell.trim());
    // A table row is "| US36 | title | feature | criterion | ... |": an empty cell before the first bar.
    if (cells.length < 6 || !/^US\d+$/.test(cells[1])) continue;
    const [, id, title, feature, criterion, , specFile] = cells;
    if (stories.has(id)) throw new Error(`${file} lists ${id} twice`);
    stories.set(id, {
      id,
      title,
      feature,
      criterion: `AC1: ${criterion}`,
      featureLabel: `Feature: ${feature}`,
      suiteLabel: `${id}: ${title}`,
      specFile: (specFile ?? "").replace(/^`|`$/g, ""),
    });
  }
  if (stories.size === 0) throw new Error(`${file} lists no story`);
  return stories;
}

/** @returns {Story} the story whose AC1 is this criterion, word for word; a criterion the table does not hold is an error */
export function storyWithCriterion(criterion) {
  const story = [...readStories().values()].find((candidate) => candidate.criterion === criterion);
  if (!story) throw new Error(`${STORIES_FILE} has no story whose AC1 is, word for word: "${criterion}"`);
  return story;
}
