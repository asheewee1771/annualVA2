// The baseline every scenario starts from, and the harness database that holds it: the System Register loaded the way
// a VA POC loads it, the copy of the database a reset goes back to, and the read-back after one. Every statement here
// runs inside the harness's own PostgreSQL container and names only the harness's two databases.
import {
  BASE_URL,
  BASELINE_SYSTEMS,
  COMPOSE_FILE,
  COMPOSE_PROJECT,
  DB_BASELINE,
  DB_NAME,
  DB_USER,
  HARNESS_ROOT,
  PERSONAS,
} from "./config.mjs";
import { AppSession } from "./http.mjs";
import { run } from "./proc.mjs";
import { registerWorkbook } from "./xlsx.mjs";

const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

/** A Docker Compose command on the harness's own project, and on nothing else. */
export function compose(...args) {
  return run("docker", ["compose", "-p", COMPOSE_PROJECT, "-f", COMPOSE_FILE, ...args], { cwd: HARNESS_ROOT });
}

/** One SQL statement through the harness's own PostgreSQL container; psql stops at the first error. */
function sql(database, statement) {
  const psql = ["psql", "-U", DB_USER, "-d", database, "-v", "ON_ERROR_STOP=1", "-qAt", "-c", statement];
  return compose("exec", "-T", "postgres", ...psql).trim();
}

/**
 * Proof that the back end the harness started is using the harness's database: it has migrated it. The database was
 * made empty moments ago by this lifecycle's own container, so nothing else can have put the migration history there.
 */
export function requireMigratedByTheApp() {
  let applied = "0";
  try {
    applied = sql(DB_NAME, "SELECT count(*) FROM flyway_schema_history");
  } catch {
    // No such table: the app migrated some other database.
  }
  if (Number(applied) < 1) {
    throw new Error(
      `The back end is up but has not migrated ${DB_NAME} in the harness's container, so it is using another ` +
        "database. Nothing was loaded into it.",
    );
  }
}

/**
 * Loads the baseline System Register the way a VA POC does (sign in as the VA POC 1 persona, upload the workbook,
 * apply the preview), with every System in the Quarter the app says is running. Nothing here grants a role or writes a
 * table directly. Returns what the app said about the calendar, for the scenarios to read.
 */
export async function loadBaselineRegister() {
  const session = new AppSession(BASE_URL);
  await session.post("/api/dev/sign-in", { persona: PERSONAS["VA POC 1"].id }, 204);
  const cycles = await session.get("/api/cycles");
  const current = cycles.cycles.find((cycle) => cycle.year === cycles.currentCycle)?.current;
  if (!current || current.handledOutsideApp) {
    throw new Error(`No Quarter is running in the app on ${cycles.businessDate}, so no VA Submission can be started.`);
  }
  const workbook = registerWorkbook(
    BASELINE_SYSTEMS.map((system) => ({
      systemId: system.systemId,
      name: system.name,
      smoEmail: PERSONAS[system.smo].email,
      cyberManagerEmail: PERSONAS[system.cyberManager].email,
      quarter: `Q${current.quarter}`,
    })),
  );
  const preview = await session.upload("/api/system-register/uploads", "system-register.xlsx", XLSX, workbook);
  if (!preview?.canApply) throw new Error("The Register preview cannot be applied: a row is Blocked.");
  const body = { previewId: preview.id, withdraw: [] };
  const applied = await session.post(`/api/system-register/previews/${preview.id}/apply`, body, 200);
  if (applied.added !== BASELINE_SYSTEMS.length) {
    throw new Error(`The Register apply added ${applied.added} Systems, not ${BASELINE_SYSTEMS.length}`);
  }
  // The Quarter's own dates, as the app has them: a scenario that needs a date relative to the Quarter reads them
  // from the lifecycle record instead of working the calendar out for itself.
  const matrix = await session.get("/api/dashboards/cycle-matrix");
  const strip = matrix.quarters.find((candidate) => candidate.quarter === current.quarter);
  if (!strip) throw new Error(`The Cycle matrix does not list Q${current.quarter}.`);
  await session.post("/api/logout");
  return {
    cycle: cycles.currentCycle,
    quarter: current.quarter,
    businessDate: cycles.businessDate,
    quarterStartsOn: strip.startsOn,
    quarterEndsOn: strip.endsOn,
  };
}

/** Copies the database as it stands into the baseline database a reset remakes it from. */
export function snapshotBaseline() {
  sql("postgres", `DROP DATABASE IF EXISTS ${DB_BASELINE} WITH (FORCE)`);
  const copy =
    `pg_dump -U ${DB_USER} -Fc ${DB_NAME} -f /tmp/baseline.dump && createdb -U ${DB_USER} ${DB_BASELINE} && ` +
    `pg_restore -U ${DB_USER} -d ${DB_BASELINE} /tmp/baseline.dump`;
  compose("exec", "-T", "postgres", "sh", "-c", copy);
}

/** Drops the harness database and remakes it from the baseline copy; the back end's pool reconnects by itself. */
export function restoreBaseline() {
  sql("postgres", `DROP DATABASE IF EXISTS ${DB_NAME} WITH (FORCE)`);
  sql("postgres", `CREATE DATABASE ${DB_NAME} TEMPLATE ${DB_BASELINE}`);
}

/** What the database must hold right after a reset: the baseline Systems, no VA Submission, nobody signed in. */
export function readBackBaseline() {
  const counts = sql(
    DB_NAME,
    "SELECT (SELECT count(*) FROM va_system) || ' ' || (SELECT count(*) FROM va_submission) || ' ' || " +
      "(SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL)",
  );
  const expected = `${BASELINE_SYSTEMS.length} 0 0`;
  if (counts !== expected) {
    throw new Error(
      `After the reset the database holds "${counts}" (Systems, VA Submissions, sign-ins), not "${expected}"`,
    );
  }
}
