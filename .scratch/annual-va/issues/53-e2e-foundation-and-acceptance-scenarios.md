# 53: Seam 2: Playwright foundation and the acceptance scenarios

**Spec:** [Annual VA spec](../spec.md), stories 36 to 38, 40, 60 to 62, 18, 48, 49, 64, 45, 81, 86.
**Release:** 1
**What to build:** A thin browser layer proves the prototyped screens end to end against the local mock stack: the SMO spreadsheet with bulk-set, the rework queue, each role's dashboard tabs and one export.
**Blocked by:** 20 (Filter chips, select-all, bulk bar and progress bar), 31 (Guided rework queue with Dispute comments and keyboard shortcuts), 33 (VA POC Quarter board and Cycle matrix), 34 (Endorse a VA Submission), 40 (Request, freeze and decide an Exemption), 44 (Management summary export (Excel), watermarked, no vulnerability detail), 01 (Obtain the mock-m-sso stack and prepare the developer machine)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 42.9 developer days across stories 36 (4.3*), 38 (3.0), 40 (4.0), 60 (5.0), 62 (1.0), 18 (4.0*), 48 (5.0), 49 (5.0), 64 (4.0), 45 (2.3), 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `e2e/` follows the e2e skill: its own `package.json` (Playwright, allure, the pinned MCP server), `e2e/README.md` project profile (start, health, base URL, the six personas, reset and isolation contract via a `dev-mcc`-only reset script), fixtures that sign in through the MockPass picker, a foundation smoke test and the staged foundation manifest; `artifacts/e2e/` is gitignored.
- [ ] Scenarios, one story each with the Allure Feature / US / AC hierarchy: SMO spreadsheet with bulk-set, progress and Submit enabling; rework queue with Dispute comment and shortcuts; dashboard tabs for SMO, Cyber Manager and VA POC including the Cycle matrix and Exemption queue; management summary export watermarked with no Finding detail.
- [ ] Business rules are not asserted in the browser; every scenario passes from a clean start with video and trace captured.

## Comments
