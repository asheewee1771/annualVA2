#!/usr/bin/env node
// The browser-test stack's lifecycle: start, reset, status, stop. See e2e/README.md, "Reset and isolation".
//
//   node scripts/stack.mjs start    its own PostgreSQL, the back end under dev-local, the front end, the baseline Register
//   node scripts/stack.mjs reset    puts the database and the file store back to the baseline (every scenario starts here)
//   node scripts/stack.mjs status   says what is running and whether it is healthy
//   node scripts/stack.mjs stop     stops and removes only what `start` made
//
// It never touches the developer's own dev-local stack: other ports, another PostgreSQL container, another file-store
// folder. `reset` refuses to run unless the app it would reset is the one this script started and is under dev-local.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import {
  compose,
  loadBaselineRegister,
  readBackBaseline,
  requireMigratedByTheApp,
  restoreBaseline,
  snapshotBaseline,
} from "./lib/baseline.mjs";
import {
  BACKEND_PORT,
  BACKEND_URL,
  BASE_URL,
  COMPOSE_PROJECT,
  DB_NAME,
  DB_PASSWORD,
  DB_PORT,
  DB_USER,
  FILE_STORE_DIR,
  FRONTEND_PORT,
  LIFECYCLE_FILE,
  LOG_DIR,
  PERSONAS,
  REPO_ROOT,
  RUNTIME_DIR,
  TEMP_DIR,
  TEMP_ENVIRONMENT,
  readLifecycle,
  stamp,
} from "./lib/config.mjs";
import { probe } from "./lib/http.mjs";
import {
  alive,
  listenerPid,
  portInUse,
  recordOf,
  startDetached,
  stillRunning,
  stopTree,
  tail,
  waitFor,
} from "./lib/proc.mjs";

const BACKEND_DIR = path.join(REPO_ROOT, "backend");
const BACKEND_LOG = path.join(LOG_DIR, "backend.log");
const FRONTEND_LOG = path.join(LOG_DIR, "frontend.log");

class Refused extends Error {}

function say(message) {
  console.log(`[e2e-stack] ${message}`);
}

async function healthy() {
  const answer = await probe(`${BACKEND_URL}/actuator/health`);
  return answer?.status === 200 && /"status"\s*:\s*"UP"/.test(answer.text);
}

/**
 * The proof that the app is under dev-local: the persona list answers 200 only there (ADR 0058, item 6; everywhere
 * else the path has no handler and answers 404), and it must list exactly the six dev personas.
 */
async function underDevLocal() {
  const answer = await probe(`${BACKEND_URL}/api/dev/personas`);
  if (answer?.status !== 200) return false;
  const expected = Object.values(PERSONAS).map((persona) => persona.id);
  try {
    const ids = JSON.parse(answer.text).map((persona) => persona.id);
    return JSON.stringify(ids.sort()) === JSON.stringify(expected.sort());
  } catch {
    return false;
  }
}

async function requirePortsFree() {
  const ports = { "the harness PostgreSQL": DB_PORT, "the back end": BACKEND_PORT, "the front end": FRONTEND_PORT };
  for (const [what, port] of Object.entries(ports)) {
    try {
      // Another run may be stopping: wait for the port rather than take it from a process this script did not start.
      await waitFor(`port ${port} to be free`, async () => !(await portInUse(port)), {
        timeoutMs: 120_000,
        everyMs: 2000,
      });
    } catch {
      throw new Refused(
        `Port ${port} (${what}) is in use by a process this harness did not start. Stop it, or name other ports with ` +
          "E2E_DB_PORT, E2E_BACKEND_PORT and E2E_FRONTEND_PORT.",
      );
    }
  }
}

/** Settings a developer's shell may hold that Spring Boot would read in place of the ones this harness gives. */
const INHERITED_SETTING = /^(SPRING_|ANNUALVA_|SERVER_|MANAGEMENT_|LOGGING_)/i;
const JVM_OPTION_VARIABLES = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"];
/** Files Spring Boot reads from the folder the back end starts in, over the profile's own. */
const LOCAL_OVERRIDES = ["config", "application.yml", "application.yaml", "application.properties"];

/**
 * The environment of the back end this harness starts: the caller's, without anything that could point the app at
 * another database, port or profile (a developer's SPRING_DATASOURCE_URL, say), plus exactly the harness's own
 * settings. It refuses where it cannot scrub: JVM options or a local configuration file that set application
 * properties. After the start, `requireMigratedByTheApp` proves which database the app is on.
 */
function backendEnvironment() {
  for (const name of JVM_OPTION_VARIABLES) {
    if (/-D(spring|annualva|server|management)\./i.test(process.env[name] ?? "")) {
      throw new Refused(`${name} sets application properties. Unset it for this command: the harness sets its own.`);
    }
  }
  for (const name of LOCAL_OVERRIDES) {
    if (fs.existsSync(path.join(BACKEND_DIR, name))) {
      throw new Refused(
        `backend/${name} would override the harness's settings. Move it away while the e2e stack runs.`,
      );
    }
  }
  const inherited = Object.entries(process.env).filter(([name]) => !INHERITED_SETTING.test(name));
  return {
    ...Object.fromEntries(inherited),
    ...TEMP_ENVIRONMENT,
    SERVER_PORT: String(BACKEND_PORT),
    ANNUALVA_DB_URL: `jdbc:postgresql://localhost:${DB_PORT}/${DB_NAME}`,
    ANNUALVA_DB_USERNAME: DB_USER,
    ANNUALVA_DB_PASSWORD: DB_PASSWORD,
    ANNUALVA_DEVLOCAL_FILESTOREDIR: FILE_STORE_DIR,
    ANNUALVA_MAIL_APPURL: BASE_URL,
  };
}

function startBackend(env) {
  const wrapper = process.platform === "win32" ? ".\\mvnw.cmd" : "./mvnw";
  const goal = ["-B", "-ntp", "spring-boot:run", "-Dspring-boot.run.profiles=dev-local"];
  return startDetached(wrapper, goal, { cwd: BACKEND_DIR, env, logFile: BACKEND_LOG });
}

function startFrontend() {
  const cwd = path.join(REPO_ROOT, "frontend");
  const vite = path.join(cwd, "node_modules", "vite", "bin", "vite.js");
  if (!fs.existsSync(vite)) throw new Refused("frontend/node_modules is missing: run `npm ci` in frontend/ first.");
  // What `npm run dev` runs, started directly so that the process this script stops is the dev server itself.
  return startDetached(process.execPath, [vite, "--port", String(FRONTEND_PORT)], {
    cwd,
    env: { ...process.env, ...TEMP_ENVIRONMENT, ANNUALVA_DEV_BACKEND: `https://localhost:${BACKEND_PORT}` },
    logFile: FRONTEND_LOG,
  });
}

async function waitForProcess(what, pid, logFile, ready, timeoutMs) {
  await waitFor(
    what,
    async () => {
      if (await ready()) return true;
      if (!alive(pid)) throw new Error(`${what}: the process ended before it was ready.\n${tail(logFile)}`);
      return false;
    },
    { timeoutMs, everyMs: 1500 },
  );
}

async function consecutiveHealth(times) {
  let streak = 0;
  await waitFor(
    `${times} healthy answers in a row from the back end`,
    async () => {
      streak = (await healthy()) ? streak + 1 : 0;
      return streak >= times;
    },
    { timeoutMs: 60_000, everyMs: 250 },
  );
}

/** The processes a lifecycle recorded, each with the id and the start time it had when this script started it. */
function processesOf(lifecycle) {
  return {
    "front end": lifecycle.frontend,
    "back end": lifecycle.backend,
    "back-end server": lifecycle.backendListener,
  };
}

async function start() {
  const existing = readLifecycle();
  if (existing) {
    if (stillRunning(Object.values(processesOf(existing))).length > 0) {
      throw new Refused(`Lifecycle ${existing.id} is still running. Run \`node scripts/stack.mjs stop\` first.`);
    }
    // A record whose processes have all gone (a crash, a restart of the machine): clear what it may have left.
    say(`lifecycle ${existing.id} is recorded but none of its processes is running: clearing it`);
    await stop();
  }
  // Checked before anything is started: a refusal here changes nothing.
  const backendEnv = backendEnvironment();
  await requirePortsFree();
  fs.rmSync(RUNTIME_DIR, { recursive: true, force: true });
  for (const dir of [LOG_DIR, FILE_STORE_DIR, TEMP_DIR]) fs.mkdirSync(dir, { recursive: true });

  const lifecycle = {
    id: `e2e-${stamp()}-${crypto.randomBytes(3).toString("hex")}`,
    startedAt: new Date().toISOString(),
    mode: "dev-local",
    baseUrl: BASE_URL,
    backendUrl: BACKEND_URL,
    database: `localhost:${DB_PORT}/${DB_NAME}`,
    composeProject: COMPOSE_PROJECT,
  };
  const save = () => fs.writeFileSync(LIFECYCLE_FILE, `${JSON.stringify(lifecycle, null, 2)}\n`);
  save();
  try {
    say(`lifecycle ${lifecycle.id}: starting PostgreSQL (Compose project ${COMPOSE_PROJECT}, port ${DB_PORT})`);
    // Recorded before the container exists, so that a start that fails halfway still removes what it made.
    lifecycle.composeStarted = true;
    save();
    compose("up", "-d", "--wait");

    say(
      `starting the back end under dev-local on port ${BACKEND_PORT} (the first start compiles it; log: ${BACKEND_LOG})`,
    );
    lifecycle.backend = recordOf(startBackend(backendEnv));
    save();
    await waitForProcess("the back end", lifecycle.backend.pid, BACKEND_LOG, healthy, 15 * 60_000);
    if (!(await underDevLocal())) throw new Error("The back end is up but does not answer as dev-local.");
    requireMigratedByTheApp();
    const listener = listenerPid(BACKEND_PORT);
    if (!listener) throw new Error(`Could not tell which process listens on port ${BACKEND_PORT}.`);
    lifecycle.backendListener = recordOf(listener);
    save();

    say(`starting the front end on port ${FRONTEND_PORT}`);
    lifecycle.frontend = recordOf(startFrontend());
    save();
    const frontEndUp = async () => (await probe(`${BASE_URL}/api/dev/personas`))?.status === 200;
    await waitForProcess("the front end", lifecycle.frontend.pid, FRONTEND_LOG, frontEndUp, 120_000);

    say("loading the baseline System Register as VA POC 1");
    lifecycle.baseline = await loadBaselineRegister();
    snapshotBaseline();
    save();
    await reset();
    lifecycle.readyAt = new Date().toISOString();
    save();
  } catch (error) {
    say(`start failed: ${error.message}`);
    await stop();
    throw error;
  }
  say(`ready. lifecycle=${lifecycle.id} mode=dev-local baseUrl=${BASE_URL}`);
}

/** Puts the stack back to its baseline and returns one line saying so; refuses unless the app is this harness's. */
async function reset() {
  const lifecycle = readLifecycle();
  if (!lifecycle?.backend) {
    throw new Refused(
      "No lifecycle of this harness is running here, so there is nothing it may reset. Run `start` first.",
    );
  }
  // Documentation more than control (the name is a constant): what protects other databases is that every statement
  // runs inside the harness's own container.
  if (!DB_NAME.endsWith("_e2e")) throw new Refused(`A reset only ever drops a database named *_e2e, not ${DB_NAME}.`);
  const recorded = lifecycle.backendListener;
  if (!recorded || listenerPid(BACKEND_PORT) !== recorded.pid || stillRunning([recorded]).length !== 1) {
    throw new Refused(`The back end on port ${BACKEND_PORT} is not the one lifecycle ${lifecycle.id} started.`);
  }
  if (!(await underDevLocal())) {
    throw new Refused(`The app at ${BACKEND_URL} is not running under the dev-local profile. Nothing was reset.`);
  }
  restoreBaseline();
  for (const entry of fs.readdirSync(FILE_STORE_DIR)) {
    fs.rmSync(path.join(FILE_STORE_DIR, entry), { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
  readBackBaseline();
  await consecutiveHealth(3);
  return `reset done. lifecycle=${lifecycle.id} baseUrl=${lifecycle.baseUrl}`;
}

async function status() {
  const lifecycle = readLifecycle();
  if (!lifecycle) {
    say("not running (no lifecycle recorded here).");
    return false;
  }
  const running = stillRunning(Object.values(processesOf(lifecycle)));
  const state = (record) => (running.includes(record) ? "running" : "stopped");
  const up = (await healthy()) && (await underDevLocal()) && (await probe(`${BASE_URL}/`))?.status === 200;
  say(
    `lifecycle=${lifecycle.id} mode=${lifecycle.mode} baseUrl=${lifecycle.baseUrl} ` +
      `backend=${state(lifecycle.backendListener)} frontend=${state(lifecycle.frontend)} healthy=${up}`,
  );
  return up;
}

async function stop() {
  const lifecycle = readLifecycle();
  if (!lifecycle) {
    say("nothing to stop (no lifecycle recorded here).");
    return;
  }
  let foreign = false;
  for (const [what, record] of Object.entries(processesOf(lifecycle))) {
    // A process id is reused once its process has gone, so each one is stopped only while it is still the process
    // this lifecycle started; an id that now belongs to something else is left alone, and said.
    if (stopTree(record) === "not ours") {
      foreign = true;
      say(`skipped the ${what}: process ${record.pid} is no longer the one this lifecycle started.`);
    }
  }
  if (lifecycle.composeStarted) compose("down", "-v", "--remove-orphans");
  // Read back: the lifecycle's ports are released. A port held by a process that was skipped above is not ours to free.
  await waitFor(
    "the lifecycle's ports to be released",
    async () =>
      foreign ||
      (!(await portInUse(BACKEND_PORT)) &&
        !(await portInUse(FRONTEND_PORT)) &&
        (!lifecycle.composeStarted || !(await portInUse(DB_PORT)))),
    { timeoutMs: 60_000 },
  );
  fs.rmSync(LIFECYCLE_FILE, { force: true });
  for (const dir of [FILE_STORE_DIR, TEMP_DIR]) {
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
  say(`stopped lifecycle ${lifecycle.id}; logs are kept in ${LOG_DIR} until the next start.`);
}

const commands = { start, reset, status, stop };
const command = commands[process.argv[2]];
if (!command) {
  console.error("Usage: node scripts/stack.mjs start|reset|status|stop");
  process.exit(2);
}
try {
  const result = await command();
  if (typeof result === "string") say(result);
  process.exit(result === false ? 1 : 0);
} catch (error) {
  // A refusal changed nothing (exit 3); anything else is a real failure (exit 1). Docker's own warnings on stderr are
  // not failures: only a non-zero exit of a command is.
  console.error(`[e2e-stack] ${error instanceof Refused ? "REFUSED" : "FAILED"}: ${error.message}`);
  process.exit(error instanceof Refused ? 3 : 1);
}
