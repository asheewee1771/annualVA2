import { execFile } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";
import { HARNESS_ROOT, readLifecycle } from "../scripts/lib/config.mjs";

const run = promisify(execFile);

/** What `node scripts/stack.mjs start` recorded about the stack the tests run against. */
export interface Lifecycle {
  id: string;
  mode: string;
  baseUrl: string;
  /** What the app said when the stack started: the running VA Cycle and Quarter, and the Quarter's own dates. */
  baseline: { cycle: number; quarter: number; businessDate: string; quarterStartsOn: string; quarterEndsOn: string };
}

/** The running lifecycle; a test run without one is refused with what to do about it. */
export function lifecycle(): Lifecycle {
  const recorded = readLifecycle() as Lifecycle | null;
  if (!recorded) {
    throw new Error("The browser-test stack is not running. Start it with `node scripts/stack.mjs start` in e2e/.");
  }
  return recorded;
}

/** A business date (YYYY-MM-DD) moved by a number of days; dates are plain days here and never shifted by a zone. */
export function shiftDate(date: string, days: number): string {
  const day = new Date(`${date}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() + days);
  return day.toISOString().slice(0, 10);
}

async function stack(command: string): Promise<string> {
  try {
    const script = path.join(HARNESS_ROOT, "scripts", "stack.mjs");
    return (await run(process.execPath, [script, command], { cwd: HARNESS_ROOT })).stdout;
  } catch (error) {
    const failed = error as { stdout?: string; stderr?: string };
    throw new Error(`stack.mjs ${command} failed:\n${failed.stdout ?? ""}${failed.stderr ?? ""}`);
  }
}

/**
 * Puts the stack back to its baseline through the documented harness command (never a private endpoint): the
 * database is remade from the baseline copy and the file store emptied. The command itself refuses to run unless the
 * app is the one the harness started under dev-local.
 */
export function resetStack(): Promise<string> {
  return stack("reset");
}

/** True when the documented status command says the stack is up and healthy. */
export async function stackIsHealthy(): Promise<boolean> {
  try {
    await stack("status");
    return true;
  } catch {
    return false;
  }
}
