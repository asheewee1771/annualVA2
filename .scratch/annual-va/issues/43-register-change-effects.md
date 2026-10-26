# 43: Register preview effects on in-flight work: withdraw, and block the rest

**Spec:** [Annual VA spec](../spec.md), stories 5, 6, 7.
**Release:** 1
**What to build:** When a re-upload retires a System with an in-flight VA Submission, the preview says so under Will withdraw and the VA POC confirms each one before it is applied; the submission becomes Withdrawn, read-only and excluded from counts and reminders. Changes of Quarter, SMO or Cyber Manager for a System with in-flight work are classified Will move and Will transfer and, in Release 1, blocked until the mid-cycle tickets of Release 2 (recorded as a decision).
**Blocked by:** 10 (Apply a Register preview; SMO and Cyber Manager roles follow the Register), 12 (Start a VA Submission), 36 (In-app notifications and clearing between the two VA POCs)
**Status:** closed
**Effort (orchestrator estimate):** about 10.8 developer days across stories 5 (5.5), 6 (3.3), 7 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] The preview classifies the effect of each row on in-flight work: WILL_WITHDRAW for a retired System with a non-final submission; WILL_MOVE for a Quarter change and WILL_TRANSFER for an SMO or Cyber Manager change on a System with a non-final submission, both shown and both Blocked from applying in Release 1 with the reason; BLOCKED when the change would give one person two steps on an in-flight submission (for example the submission's Cyber Manager becoming its SMO).
- [x] A WILL_WITHDRAW row whose VA Submission has any file still scanning, including a rescan (ticket 21; decision 2026-10-18), is Blocked from applying with that reason until the scan settles; proven by a Seam 1 test.
- [x] Applying requires an explicit per-row confirmation for every WILL_WITHDRAW row, shown in the Will withdraw group of the Register screen; the apply sets the submission Withdrawn (read-only, excluded from dashboard counts and from reminders), audits it and notifies the SMO.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Note from ticket 12 (2026-10-13)

The SMO inbox row's `quarter` comes from the Register's current Quarter, while its due dates come from the stored VA Submission (copied from the Quarter when it was started, ADR 0020). Once the Register can move a System to another Quarter, a Draft would show the new Quarter beside the old dates. This ticket's WILL_MOVE handling should decide which wins (re-date the Draft, or show the stored Quarter) and cover it with a Seam 1 test.

### Note from ticket 37 (2026-10-25)

The withdrawal notice to the SMO/Tech Lead must also be mailed (ticket 37's acceptance names withdrawn among the Register-change messages that reach the SMO by email). Adding the `NoticeAction` constant forces a choice in `MailTemplate.of` (ADR 0046): map it to `REGISTER_CHANGE` with the `change_kind` "Withdrawn", and prove with a Seam 1 test that the SMO receives that mail (`CapturingMailRelay`, `MailSeam1`).

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `3e6d422`).

**Implemented:** Register preview classifies effects on in-flight work, holds back moves and transfers in Release 1, per-row VA POC confirmation of withdrawals, Withdrawn notice and mail. ADR 0049, migration V34 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1019 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/43-register-change-effects-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0049); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
