# 53: Seam 2: Playwright foundation and the acceptance scenarios

**Spec:** [Annual VA spec](../spec.md), stories 36 to 38, 40, 60 to 62, 18, 48, 49, 64, 45, 81, 86.
**Release:** 1
**What to build:** A thin browser layer proves the prototyped screens end to end against the app running under `dev-local` on a developer's machine (ticket 63): the SMO spreadsheet with bulk-set, the rework queue, each role's dashboard tabs and one export.
**Blocked by:** 20 (Filter chips, select-all, bulk bar and progress bar), 31 (Guided rework queue with Dispute comments and keyboard shortcuts), 33 (VA POC Quarter board and Cycle matrix), 34 (Endorse a VA Submission), 40 (Request, freeze and decide an Exemption), 44 (Management summary export (Excel), watermarked, no vulnerability detail), 63 (dev-local profile with persona sign-in). No longer blocked by 01: see the comment of 2026-11-22.
**Status:** closed
**Effort (orchestrator estimate):** about 42.9 developer days across stories 36 (4.3*), 38 (3.0), 40 (4.0), 60 (5.0), 62 (1.0), 18 (4.0*), 48 (5.0), 49 (5.0), 64 (4.0), 45 (2.3), 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `e2e/` follows the e2e skill: its own `package.json` (Playwright, allure, the pinned MCP server), `e2e/README.md` project profile (start, health, base URL, the six personas, reset and isolation contract via a `dev-local`-only reset script), fixtures that sign in through the dev persona picker (`/dev-sign-in`), a foundation smoke test and the staged foundation manifest; `artifacts/e2e/` is gitignored.
- [x] Scenarios, one story each with the Allure Feature / US / AC hierarchy: SMO spreadsheet with bulk-set, progress and Submit enabling; rework queue with Dispute comment and shortcuts; dashboard tabs for SMO, Cyber Manager and VA POC including the Cycle matrix and Exemption queue; management summary export watermarked with no Finding detail.
- [x] Business rules are not asserted in the browser; every scenario passes from a clean start with video and trace captured.

## Comments

**2026-11-22, retargeted to `dev-local` (decided by the user):** the ticket was written against the mock single sign-on stack (`mock-m-sso`, ticket 01), which is not on the developer's machine, so it could not be built as written. It now targets the `dev-local` profile of ticket 63: sign-in through the dev persona picker, local fakes for the scanner, file store and mail, and only PostgreSQL in Docker. The reset script must refuse to run unless the app is under `dev-local`. Sign-in through the MockPass picker under `dev-mcc` is left for a follow-up once ticket 01 has delivered `mock-m-sso`; the real single sign-on round trip is therefore not covered by these scenarios.

### Verification (2026-11-22)

**What was built.** An `e2e/` Playwright harness with its own `package.json` (exact pins: `@playwright/test` 1.65.1, `allure` 3.33.0, `allure-playwright` 3.15.0, `allure-js-commons` 3.15.0, `@playwright/mcp` 0.0.86), a project profile in `e2e/README.md`, fixtures that sign in through `/dev-sign-in`, a foundation smoke test, the staged foundation manifest and 14 scenarios, one per story, with the Allure Feature / US / AC hierarchy. The stack runs beside a developer's own: its own PostgreSQL container (port 5433, data in tmpfs), back end on 8444 under `dev-local` with a scrubbed environment, front end on 5174. Before every scenario the database is restored from a baseline; the reset refuses to run unless the app proves it is `dev-local`. `frontend/vite.config.ts` now takes its proxy target from `ANNUALVA_DEV_BACKEND` (default unchanged). Commits `58f2fa5`, `1f4b244`, `c72618c`, merged by `4971e54`.

**Decided by the user on 2026-11-22.** The ticket targets `dev-local`, not `mock-m-sso` (ticket 01). The criteria in `e2e/stories.md` are approved, with US37 (the bulk bar stays in view while the list scrolls) and US38 (the Disputed count) restored. Access rules stay at Seam 1: no scenario asserts that someone else's System is not listed. The one-pass build and single campaign are accepted on condition that `check-evidence.mjs` fails a run with fewer scenarios than expected or a title, sub-suite or criterion line that does not match `stories.md` word for word; it does.

**How it was verified.**
- Final clean-start runs: smoke 1 of 1; acceptance 14 of 14 in 5.3 minutes; `check-evidence` 14 scenarios, 0 problems, each with a non-empty video and trace (campaigns `artifacts/e2e/campaigns/20261122T125805Z-smoke` and `20261122T125947Z-acceptance` in the ticket's worktree, gitignored).
- Front-end gate: `npm run check` exits 0 on the branch and on merged `main`. The e2e TypeScript type-checks, Prettier passes, the foundation manifest verifies. No back-end file changed.
- `/code-reviewer`: PASS, no Critical, High or Medium (`artifacts/code-reviewer/53-e2e-foundation-and-acceptance-scenarios-compliance.html`). Three rounds; the report says which check saw which round.

**Not covered.** The MockPass sign-in under `dev-mcc` (waits on ticket 01); a real single sign-on round trip; a clock, so no scenario can make a window pass; the lifecycle scripts were run on Windows only. `@playwright/mcp` depends on an alpha Playwright in every release; the tests start the stable runner by path, and the README says so.

All three boxes are ticked.
