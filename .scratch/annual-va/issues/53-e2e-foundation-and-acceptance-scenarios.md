# 53: Seam 2: Playwright foundation and the acceptance scenarios

**Spec:** [Annual VA spec](../spec.md), stories 36 to 38, 40, 60 to 62, 18, 48, 49, 64, 45, 81, 86.
**Release:** 1
**What to build:** A thin browser layer proves the prototyped screens end to end against the app running under `dev-local` on a developer's machine (ticket 63): the SMO spreadsheet with bulk-set, the rework queue, each role's dashboard tabs and one export.
**Blocked by:** 20 (Filter chips, select-all, bulk bar and progress bar), 31 (Guided rework queue with Dispute comments and keyboard shortcuts), 33 (VA POC Quarter board and Cycle matrix), 34 (Endorse a VA Submission), 40 (Request, freeze and decide an Exemption), 44 (Management summary export (Excel), watermarked, no vulnerability detail), 63 (dev-local profile with persona sign-in). No longer blocked by 01: see the comment of 2026-11-22.
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 42.9 developer days across stories 36 (4.3*), 38 (3.0), 40 (4.0), 60 (5.0), 62 (1.0), 18 (4.0*), 48 (5.0), 49 (5.0), 64 (4.0), 45 (2.3), 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `e2e/` follows the e2e skill: its own `package.json` (Playwright, allure, the pinned MCP server), `e2e/README.md` project profile (start, health, base URL, the six personas, reset and isolation contract via a `dev-local`-only reset script), fixtures that sign in through the dev persona picker (`/dev-sign-in`), a foundation smoke test and the staged foundation manifest; `artifacts/e2e/` is gitignored.
- [ ] Scenarios, one story each with the Allure Feature / US / AC hierarchy: SMO spreadsheet with bulk-set, progress and Submit enabling; rework queue with Dispute comment and shortcuts; dashboard tabs for SMO, Cyber Manager and VA POC including the Cycle matrix and Exemption queue; management summary export watermarked with no Finding detail.
- [ ] Business rules are not asserted in the browser; every scenario passes from a clean start with video and trace captured.

## Comments

**2026-11-22, retargeted to `dev-local` (decided by the user):** the ticket was written against the mock single sign-on stack (`mock-m-sso`, ticket 01), which is not on the developer's machine, so it could not be built as written. It now targets the `dev-local` profile of ticket 63: sign-in through the dev persona picker, local fakes for the scanner, file store and mail, and only PostgreSQL in Docker. The reset script must refuse to run unless the app is under `dev-local`. Sign-in through the MockPass picker under `dev-mcc` is left for a follow-up once ticket 01 has delivered `mock-m-sso`; the real single sign-on round trip is therefore not covered by these scenarios.
