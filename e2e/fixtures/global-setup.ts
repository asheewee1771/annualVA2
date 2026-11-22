import fs from "node:fs";
import { lifecycle, stackIsHealthy } from "./stack.ts";

function requireEmpty(name: string): void {
  const dir = process.env[name];
  if (!dir)
    throw new Error(`${name} is required when E2E_CAMPAIGN_SUITE is set: an evidence run owns its result folders.`);
  if (fs.existsSync(dir) && fs.readdirSync(dir).length > 0) {
    throw new Error(`${name} (${dir}) is not empty: an evidence run never adds to, or copies, earlier results.`);
  }
}

/**
 * Runs once per run. An evidence run's results must be its own and complete, so its folders must start empty and a
 * listing is not a run; and no test starts unless the harness's stack is up, because a test never starts services.
 */
export default async function globalSetup(): Promise<void> {
  if (process.env.E2E_CAMPAIGN_SUITE) {
    if (process.argv.includes("--list")) {
      throw new Error("--list is not allowed in an evidence run: it records no evidence.");
    }
    requireEmpty("ALLURE_RESULTS_DIR");
    requireEmpty("PLAYWRIGHT_OUTPUT_DIR");
  }
  const running = lifecycle();
  if (!(await stackIsHealthy())) {
    throw new Error(
      `Lifecycle ${running.id} is recorded but not healthy. Run \`node scripts/stack.mjs stop\`, then start.`,
    );
  }
}
