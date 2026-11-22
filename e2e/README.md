# Browser tests (Seam 2)

A thin Playwright layer that proves the prototyped screens end to end against the app running under the `dev-local`
profile on one machine (ADR 0058): the SMO spreadsheet with bulk-set, the rework queue, each role's dashboard tabs and
one export. Business rules and access rules are never tested here (ADR 0009); they are Seam 1's. The scenarios, and
the story and criterion each one binds to, are listed in [stories.md](stories.md).

Sign-in is the dev persona picker (`/dev-sign-in`). The real single sign-on round trip is not covered: that waits for
`mock-m-sso`.

## Project profile

| | |
|---|---|
| Install | `cd e2e && npm ci`, then `npm run playwright -- install chromium --only-shell`; `cd frontend && npm ci` if it has not been done |
| Needs | Docker running, JDK 21, Node 22.2 or later; ports 5433, 8444 and 5174 free |
| Start | `node scripts/stack.mjs start` (in `e2e/`) |
| Health | `node scripts/stack.mjs status`; it reads `https://127.0.0.1:8444/actuator/health`, `/api/dev/personas` and the front end |
| Base URL | `https://localhost:5174` (self-signed certificate; Playwright is told to accept it for this stack only) |
| Mode | `dev-local`, the only one; every lifecycle and evidence run records it |
| Test accounts | The six dev personas, signed in through the picker by the `signInAs` fixture: SMO A, SMO B, Cyber Manager A, Cyber Manager B, VA POC 1, VA POC 2. No password exists |
| Test environment | The harness's own stack on this machine: its own PostgreSQL container, back end and front end. Never a developer's dev stack, never a deployed environment |
| Reset | `node scripts/stack.mjs reset`; the `cleanStart` fixture runs it before every test |
| Stop | `node scripts/stack.mjs stop` |
| One run from a clean start | `npm run smoke` (the foundation smoke test), `npm test` (the acceptance scenarios) |
| Evidence | `artifacts/e2e/campaigns/<utc-id>[-<label>]-<project>/`: `allure-results/`, `allure-report/`, `test-results/` (videos, traces), `run.json`. Git-ignored |
| Lifecycle state | `e2e/.runtime/`: `lifecycle.json`, `logs/`, the local file store, temporary files. Git-ignored, and emptied by every start |

"Campaign" in a folder or variable name here is the e2e skill's word for one evidence run. It has nothing to do with a
VA Cycle.

If Node cannot validate your organisation's proxy certificate during an install, set `NODE_OPTIONS=--use-system-ca`
for that command.

### Keeping the downloads off the system drive (optional)

Playwright keeps its browsers in the user profile unless told otherwise. To keep them in the checkout instead, set
`PLAYWRIGHT_BROWSERS_PATH` to `e2e/.playwright-browsers` (git-ignored) for the install, and give npm a cache folder on
the drive you prefer (outside `e2e/.runtime/`, which every start empties):

```bash
cd e2e
npm ci --cache /d/npm-cache
PLAYWRIGHT_BROWSERS_PATH="$PWD/.playwright-browsers" npm run playwright -- install chromium --only-shell
```

`scripts/run.mjs` uses `e2e/.playwright-browsers` by itself when the folder exists and `PLAYWRIGHT_BROWSERS_PATH` is not
set. For a direct `npm run playwright -- test`, set the variable yourself. The processes the harness starts keep their
temporary files in `e2e/.runtime/tmp`.

## The stack and its personas

`stack.mjs start` brings up, in this order, and records each in `e2e/.runtime/lifecycle.json`:

1. **PostgreSQL** from `compose.e2e.yml`: Compose project `annualva-e2e`, port 5433, data in memory. It is not the
   `postgres` service of the repository's `compose.yml`.
2. **The back end** with `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev-local`, on port 8444, against the
   database `annualva_e2e`, its local file store in `e2e/.runtime/files`. Only the port, the database address and the
   file-store folder differ from a developer's own dev-local run; they are set through the environment. The first start
   compiles the back end and takes a few minutes.
3. **The front end** (what `npm run dev` runs, with `--port 5174`), proxying to port 8444 (`ANNUALVA_DEV_BACKEND`,
   read by `frontend/vite.config.ts`).
4. **The baseline System Register** (`scripts/lib/baseline.mjs`), loaded the way a VA POC loads it: signed in as
   VA POC 1, the workbook uploaded and the preview applied through the app's own endpoints. Nothing grants a role or
   writes a table directly.

| System | SMO/Tech Lead | Cyber Manager | Quarter |
|---|---|---|---|
| `E2E-001` E2E Payments Gateway | SMO A | Cyber Manager A | the Quarter the app is running |
| `E2E-002` E2E Staff Directory | SMO A | Cyber Manager A | the Quarter the app is running |
| `E2E-003` E2E Records Archive | SMO B | Cyber Manager B | the Quarter the app is running |

VA POC 1 and VA POC 2 are VA POCs from configuration, as under any `dev-local` run. The Quarter is asked of the app
(`GET /api/cycles`) when the stack starts, because `dev-local` has no clock to set (ADR 0058, item 7); the answer, with
the Quarter's dates, is in the lifecycle record for the scenarios to read.

It prints `ready. lifecycle=<id> mode=dev-local baseUrl=https://localhost:5174`. It then copies the database into
`annualva_e2e_base`: the baseline a reset goes back to.

## Reset and isolation

**What is isolated.** The browser-test stack shares no port and no data with a developer's dev-local stack: other
ports (5433, 8444, 5174 against 5432, 8443, 5173), another PostgreSQL container with its data in memory, another
file-store folder. A developer's `annualva` database, its Docker volume and `backend/.dev-local/` are never read,
written or removed. `E2E_DB_PORT`, `E2E_BACKEND_PORT` and `E2E_FRONTEND_PORT` move the ports. One thing is shared: the
back end compiles into `backend/target`, so do not run a Maven build of the back end (`./mvnw clean verify`, say) in
the same checkout while the browser-test stack is starting.

**The back end cannot be pointed elsewhere by accident.** Its environment is the caller's without every `SPRING_*`,
`ANNUALVA_*`, `SERVER_*`, `MANAGEMENT_*` and `LOGGING_*` variable (a developer's `SPRING_DATASOURCE_URL`, say), plus
the harness's own settings. `start` refuses, before starting anything, when `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS`
sets an application property, or when `backend/config/` or a `backend/application.*` file would override the
profile's. Once the back end is up, `start` requires the dev-local proof below and that the app has migrated
`annualva_e2e` in the harness's own container, before it loads anything.

**What a reset wipes.** `stack.mjs reset` drops the database `annualva_e2e` in the harness's container and remakes it
from `annualva_e2e_base`, and empties `e2e/.runtime/files`. So every VA Submission, Finding, review, Exemption, export,
notification, audit entry and sign-in made since the stack started is gone, and the three baseline Systems are Not
Started again. Nothing else is touched. The back end keeps running: its connection pool reconnects by itself, and
everyone is signed out.

**When it refuses** (exit code 3, nothing changed):

- no lifecycle of this harness is recorded in `e2e/.runtime/`;
- the process listening on the back-end port is not the one the lifecycle recorded (same process id and same start
  time), or the lifecycle recorded none;
- the app does not answer as `dev-local`: `GET /api/dev/personas` must answer 200 with exactly the six dev personas,
  which it does under no other profile (ADR 0058, item 6).

What keeps a reset away from any other database is where it runs: every statement goes through
`docker compose -p annualva-e2e exec postgres`, the harness's own container, and names only the harness's two
databases.

**Read-back.** After the copy, the reset reads the database back (three Systems, no VA Submission, nobody signed in)
and waits for three healthy answers in a row from the back end. A failure there is exit code 1.

**Start and stop.** `start` refuses when a lifecycle is still running, and when a port it needs is taken it waits up to
two minutes and then refuses; it never stops a process it did not start. A failed start stops what it had started.
`stop` stops each recorded process only while it is still the process the lifecycle started: the lifecycle records
each process id with its start time, and an id that now belongs to another process (the record outlived a crash or a
restart of the machine) is skipped and said, never stopped. It then removes the Compose project `annualva-e2e` with
its data (`docker compose down -v` on that project only) and waits until the three ports are free. Logs stay in
`e2e/.runtime/logs` until the next start.

**Exit codes of `stack.mjs`.** 0 done; 1 failed; 2 unknown command; 3 refused. Docker's and Maven's own warnings are
not failures: only a non-zero exit is.

**In a test.** A spec never resets, starts or stops anything and never calls the API. The `cleanStart` fixture
(`fixtures/test.ts`) runs the documented reset before each test, and Playwright runs one test at a time. A test run
without a healthy lifecycle is refused before the first test.

## Running

```bash
cd e2e
npm run smoke            # start, foundation smoke test, report, evidence check, stop
npm test                 # start, the acceptance scenarios, report, evidence check, stop
```

`scripts/run.mjs` starts the stack unless a healthy one is already running here, runs the Playwright project into an
evidence folder of its own, builds the Allure report (`allure awesome --group-by feature,suite,subSuite`), checks the
evidence, and stops the stack if it started it. `E2E_KEEP_STACK=1` leaves the stack up. `E2E_CAMPAIGN_LABEL=ticket-53`
(letters, digits, hyphens) puts a label of yours into the run's id. Arguments after the project name go to Playwright,
for example `node scripts/run.mjs acceptance tests/us37-bulk-bar.spec.ts`.

**The evidence check** (`scripts/check-evidence.mjs`) reads the raw Allure results, not the report, and fails the run
unless:

- every scenario passed (or is a declared skip) and carries a non-empty video, a non-empty trace and a Test plan;
- the whole set was recorded: one result per story of `stories.md` for `npm test`, one for `npm run smoke`. A run that
  lost a scenario fails and names the stories that are missing. With Playwright arguments of your own the number is
  yours to judge and is not checked;
- for the acceptance scenarios, each result binds to its story of `stories.md` word for word: the test's title, the
  Allure sub-suite and the criterion line of the description are the story's AC1, the feature and the suite are the
  story's, every outcome is an `Assertion: [AC1]` step holding one `Evidence: expected: … | observed: …` step, and no
  story is there twice.

While writing a scenario: `node scripts/stack.mjs start`, then `npm run playwright -- test --project acceptance <spec>`
as often as needed (results go to `artifacts/e2e/scratch/`, overwritten), then `node scripts/stack.mjs stop`.

Video and trace are recorded for every test by `playwright.config.ts`; no spec decides it. An evidence run
(`E2E_CAMPAIGN_SUITE` set, which `run.mjs` does) requires its result folders to be empty and refuses `--list`. Traces
hold the local stack's session cookies: keep them out of tickets and shared drives.

To browse the reports of every evidence run, use the e2e skill's portal from the repository root:

```bash
python .claude/skills/e2e/scripts/serve_e2e_reports.py start --artifacts-root artifacts/e2e --harness-root e2e
```

In Allure, scenarios are grouped Feature, then `US<n>`, then the criterion (`AC1: …`).

## Writing a scenario

- One spec file per story, one test per criterion. Add the story's row to [stories.md](stories.md) first; the test's
  title is that criterion, word for word, and `await story("<persona>")` at the top of the test finds the story by
  that title and takes the feature and the suite from the table. It stops a test whose title is no story's criterion,
  or whose file is not the table's Spec file.
- The spec itself keeps the story's sentence and the criterion as text, the `allure.description(...)` call, and the
  `Test plan` attachment before the first browser action (the e2e skill's `references/allure-evidence.md` says what
  goes in it). The run it belongs to, the sub-suite and the `e2e_configuration` parameter are set for every test by
  a fixture.
- Each outcome goes through `assertion("Assertion: [AC1] actor: … | …", { expected, observed }, async () => { … })`
  from `fixtures/test.ts`, with retrying, web-first matchers inside and no waits of the same kind before it. The
  `Evidence:` step opens with what is expected; `observed` (a string, or a function that reads the screen with
  `flatText`) is read once the matchers are done or have failed, and the step is renamed with it.
- Find elements by role, label and visible text. No test ids, no API calls, no process control in a spec.
- A spec imports only from `fixtures/`. Shared journeys (`fixtures/journeys.ts`) set a scenario up; the scenario proves
  its own outcome.
- Scan Reports are made by `fixtures/scan-reports.ts`, dated the day before the app's business date. The samples under
  `backend/src/test/resources/samples/` are dated July 2027 and the app refuses them until then.

## The foundation manifest

`e2e-foundation.json` lists the shared harness files with their SHA-256, and is staged in git. Check it with

```bash
python .claude/skills/e2e/scripts/verify_allure_e2e.py --verify-foundation e2e/e2e-foundation.json
```

After changing a listed file, the harness owner regenerates it and stages it again (the file list is
`scripts/foundation-files.txt`):

```bash
python .claude/skills/e2e/scripts/create_e2e_foundation_manifest.py e2e/e2e-foundation.json $(sed 's|^|e2e/|' e2e/scripts/foundation-files.txt)
git add e2e/e2e-foundation.json $(sed 's|^|e2e/|' e2e/scripts/foundation-files.txt)
```

`stories.md` and the story specs are not in it: a later story adds its own row and its own spec.

## Two Playwrights, and which one runs the tests

The tests run on `@playwright/test` 1.65.1, a stable release, and on nothing else: `run.mjs` and
`npm run playwright` start `node_modules/@playwright/test/cli.js` by its path, and that package carries its own
Playwright 1.65.1. Use `npm run playwright -- …` and not `npx playwright …`.

`@playwright/mcp` 0.0.86 is pinned because the e2e skill asks for a project-local MCP browser for its agents. Every
release of it depends on a pre-release Playwright (0.0.86 on `1.66.0-alpha-2026-11-18`; 0.0.84 and 0.0.85 on other
alphas), so no version matches the stable test runner. The alpha is confined to the MCP server: no script, fixture or
spec here imports `playwright` or `@playwright/mcp`, and the lock file pins both trees with integrity hashes. Whoever
wants no pre-release in the lock file can remove `@playwright/mcp` without touching the tests.

The MCP server is started without installing anything, limited to this stack's origin:

```bash
npx --prefix e2e --no-install playwright-mcp --caps=devtools --ignore-https-errors --allowed-origins https://localhost:5174
```

`--ignore-https-errors` is for this local stack's self-signed certificates only. No MCP registration is committed.

## Where this differs from the e2e skill

The user accepted these adaptations on 2026-11-22, on the condition that the evidence check closes the gap the skill's
own verifier would have covered (the count of scenarios and the word-for-word binding to `stories.md`, above).

- **Foundation and scenarios in one pass.** The skill sets up the foundation in one invocation and runs each story
  later, with separate discoverer, author and executor agents and an independent review between them. Here the
  scenarios were written with the foundation, by one author, and the skill's hand-offs (`story-analysis.json`, the
  author and executor hand-offs, its preflight and final verifier) were not produced.
- **One evidence run for all the stories**, not one per story.
- **`scripts/check-evidence.mjs` stands in for the skill's final verifier.** It checks what is listed under "The
  evidence check"; it does not judge whether an assertion proves its criterion, which the code review does.
- **Criteria.** The spec's stories have no numbered criteria; [stories.md](stories.md) gives each scenario its one, and
  the user approved that table on 2026-11-22.
- **Access rules stay at Seam 1** (the user's decision of 2026-11-22): a scenario reads that a person's own Systems are
  listed, and never that someone else's are not.
- **No inventory of common journeys.** The shared journeys are in `fixtures/journeys.ts`; the skill's
  `e2e/common-journeys.json`, with a smoke test per journey, was not made.
- **No host adapters.** The skill's three role agents were not copied into `.claude/agents/`: `.claude/` is not
  committed in this repository.
- **The stack is the app under `dev-local`**, not `mock-m-sso`.

## Limits

- The calendar is the real one. The baseline puts the Systems in the Quarter the app is running, so the scenarios do not
  depend on the date, but nothing here can make a window pass, a VA Submission Overdue or a VA Cycle close.
- The lifecycle script has been run on Windows only. Its Linux and macOS paths (process groups, `ps`, `lsof`) are
  written but not tried.
- One browser-test stack per machine: the Compose project name and the ports are fixed unless the port variables say
  otherwise.
- The workbook reader and writer (`scripts/lib/xlsx.mjs`) are small on purpose and know only what the Register upload
  and the management summary need; a workbook with another layout may need more.
