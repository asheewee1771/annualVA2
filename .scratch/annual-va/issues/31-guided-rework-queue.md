# 31: Guided rework queue with Dispute comments and keyboard shortcuts

**Spec:** [Annual VA spec](../spec.md), stories 60, 61, 62.
**Release:** 1
**What to build:** An SMO whose submission was Returned for Rework works through a queue one Finding at a time, Disputed first, sees the Cyber Manager's comment, picks a status with keys 1 to 4 and saves with Enter, then resubmits.
**Blocked by:** 30 (Return for rework or pass to endorsement)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 8.0 developer days across stories 60 (5.0), 61 (2.0), 62 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/submissions/{id}/rework-queue` returns the Findings needing attention in the order Disputed, then new Critical/High pending, then new Medium/Low/Informational pending (the Target Date Missed and Carried Over stages arrive with Release 2), each with the latest Dispute comment and the current proposal.
- [ ] A new proposal on a Disputed Finding resets its review state to PENDING; resubmitting through `POST /api/submissions/{id}/submit` moves the submission back to In Review.
- [ ] The rework screen shows one Finding at a time with the stage list, the Dispute comment as text, four status buttons bound to keys 1 to 4 and Enter to save and advance (ignored while typing in a field), with a visible shortcut hint and a Resubmit action.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
