# 45: Management summary export (PDF) with a diagonal watermark

**Spec:** [Annual VA spec](../spec.md), stories 81, 86.
**Release:** 1
**What to build:** The same management summary is available as a PDF with the classification watermarked across every page.
**Blocked by:** 44 (Management summary export (Excel), watermarked, no vulnerability detail)
**Status:** closed
**Effort (orchestrator estimate):** about 5.3 developer days across stories 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] Format PDF renders the same content with the PDF library chosen in ADR 0016: a header and footer with the classification and a diagonal semi-transparent watermark on every page.
- [x] The PDF contains no vulnerability detail (text extraction in the test), and is downloadable and audited like the Excel export.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `12e1ea4`).

**Implemented:** Management summary as a PDF renderer (Apache PDFBox 3.0.8) beside the Excel one on a shared table model. ADR 0054, migration V35 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1075 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/45-management-summary-pdf-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0054); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
