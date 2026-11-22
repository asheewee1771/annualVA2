// Where the browser-test stack lives. One place for its ports, names and folders, so the lifecycle script, the runner
// and the Playwright configuration cannot disagree. Every value is local to one machine.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

/** e2e/ */
export const HARNESS_ROOT = path.resolve(here, "..", "..");
/** The repository root (the folder holding backend/, frontend/ and e2e/). */
export const REPO_ROOT = path.resolve(HARNESS_ROOT, "..");
/** State the lifecycle owns and that must outlive one runner invocation: git-ignored, never a report folder. */
export const RUNTIME_DIR = path.join(HARNESS_ROOT, ".runtime");
export const LIFECYCLE_FILE = path.join(RUNTIME_DIR, "lifecycle.json");
export const LOG_DIR = path.join(RUNTIME_DIR, "logs");
/** The local file store of the back end this harness starts (never backend/.dev-local, the developer's own). */
export const FILE_STORE_DIR = path.join(RUNTIME_DIR, "files");
/** Where the processes the harness starts (back end, front end, browsers) put their temporary files. */
export const TEMP_DIR = path.join(RUNTIME_DIR, "tmp");
/** The same folder under the three names a process may look for its temporary files by. */
export const TEMP_ENVIRONMENT = { TEMP: TEMP_DIR, TMP: TEMP_DIR, TMPDIR: TEMP_DIR };
/** Playwright's browsers, when they are installed inside the checkout (optional; see e2e/README.md). */
export const LOCAL_BROWSERS_DIR = path.join(HARNESS_ROOT, ".playwright-browsers");
export const ARTIFACTS_ROOT = path.join(REPO_ROOT, "artifacts", "e2e");

function port(name, fallback) {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isInteger(value) || value < 1024 || value > 65535) {
    throw new Error(`${name} must be a port between 1024 and 65535`);
  }
  return value;
}

/** Not 5432, 8443 and 5173: a developer's own dev-local stack keeps running beside this one. */
export const DB_PORT = port("E2E_DB_PORT", 5433);
export const BACKEND_PORT = port("E2E_BACKEND_PORT", 8444);
export const FRONTEND_PORT = port("E2E_FRONTEND_PORT", 5174);

export const BASE_URL = `https://localhost:${FRONTEND_PORT}`;
export const BACKEND_URL = `https://127.0.0.1:${BACKEND_PORT}`;

export const COMPOSE_PROJECT = "annualva-e2e";
export const COMPOSE_FILE = path.join(HARNESS_ROOT, "compose.e2e.yml");
/** The only database a reset drops, and the copy it is remade from. The names are fixed on purpose. */
export const DB_NAME = "annualva_e2e";
export const DB_BASELINE = "annualva_e2e_base";
export const DB_USER = "annualva";
export const DB_PASSWORD = "annualva-e2e-local";

/** The six dev personas of backend/src/main/resources/dev-personas.yml, by the name the picker shows. */
export const PERSONAS = {
  "SMO A": { id: "11111111-0000-4000-8000-000000000001", email: "smo.a@dev.annualva.test" },
  "SMO B": { id: "11111111-0000-4000-8000-000000000002", email: "smo.b@dev.annualva.test" },
  "Cyber Manager A": { id: "11111111-0000-4000-8000-000000000003", email: "cm.a@dev.annualva.test" },
  "Cyber Manager B": { id: "11111111-0000-4000-8000-000000000004", email: "cm.b@dev.annualva.test" },
  "VA POC 1": { id: "11111111-0000-4000-8000-000000000005", email: "poc.1@dev.annualva.test" },
  "VA POC 2": { id: "11111111-0000-4000-8000-000000000006", email: "poc.2@dev.annualva.test" },
};

/**
 * The System Register every scenario starts from. The Quarter is filled in when the stack starts with the Quarter the
 * app says is running, so the Systems are in the current Quarter whatever day the suite runs (dev-local has no clock
 * to set, ADR 0058 item 7).
 */
export const BASELINE_SYSTEMS = [
  { systemId: "E2E-001", name: "E2E Payments Gateway", smo: "SMO A", cyberManager: "Cyber Manager A" },
  { systemId: "E2E-002", name: "E2E Staff Directory", smo: "SMO A", cyberManager: "Cyber Manager A" },
  { systemId: "E2E-003", name: "E2E Records Archive", smo: "SMO B", cyberManager: "Cyber Manager B" },
];

/** What `stack.mjs start` recorded about the stack that is running, or null when none is recorded here. */
export function readLifecycle() {
  try {
    return JSON.parse(fs.readFileSync(LIFECYCLE_FILE, "utf8"));
  } catch {
    return null;
  }
}

/** A UTC timestamp fit for a folder name and an id, for example 20261122T041930Z. */
export function stamp(date = new Date()) {
  return date.toISOString().replace(/[-:]/g, "").replace(/\..*/, "Z");
}
