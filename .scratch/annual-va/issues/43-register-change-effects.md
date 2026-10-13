# 43: Register preview effects on in-flight work: withdraw, and block the rest

**Spec:** [Annual VA spec](../spec.md), stories 5, 6, 7.
**Release:** 1
**What to build:** When a re-upload retires a System with an in-flight VA Submission, the preview says so under Will withdraw and the VA POC confirms each one before it is applied; the submission becomes Withdrawn, read-only and excluded from counts and reminders. Changes of Quarter, SMO or Cyber Manager for a System with in-flight work are classified Will move and Will transfer and, in Release 1, blocked until the mid-cycle tickets of Release 2 (recorded as a decision).
**Blocked by:** 10 (Apply a Register preview; SMO and Cyber Manager roles follow the Register), 12 (Start a VA Submission), 36 (In-app notifications and clearing between the two VA POCs)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 10.8 developer days across stories 5 (5.5), 6 (3.3), 7 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] The preview classifies the effect of each row on in-flight work: WILL_WITHDRAW for a retired System with a non-final submission; WILL_MOVE for a Quarter change and WILL_TRANSFER for an SMO or Cyber Manager change on a System with a non-final submission, both shown and both Blocked from applying in Release 1 with the reason; BLOCKED when the change would give one person two steps on an in-flight submission (for example the submission's Cyber Manager becoming its SMO).
- [ ] Applying requires an explicit per-row confirmation for every WILL_WITHDRAW row, shown in the Will withdraw group of the Register screen; the apply sets the submission Withdrawn (read-only, excluded from dashboard counts and from reminders), audits it and notifies the SMO.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Note from ticket 12 (2026-10-13)

The SMO inbox row's `quarter` comes from the Register's current Quarter, while its due dates come from the stored VA Submission (copied from the Quarter when it was started, ADR 0020). Once the Register can move a System to another Quarter, a Draft would show the new Quarter beside the old dates. This ticket's WILL_MOVE handling should decide which wins (re-date the Draft, or show the stored Quarter) and cover it with a Seam 1 test.
