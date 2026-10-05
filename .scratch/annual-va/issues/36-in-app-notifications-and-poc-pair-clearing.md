# 36: In-app notifications and clearing between the two VA POCs

**Spec:** [Annual VA spec](../spec.md), stories 92, 69.
**Release:** 1
**What to build:** Every user has an in-app list of their notifications; items for the VA POCs go to both and clear for one with "handled by (name)" when the other acts.
**Blocked by:** 34 (Endorse a VA Submission)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 5.4 developer days across stories 92 (2.0), 69 (3.4); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Workflow events create notifications after commit, each carrying only System, state, action, due date and link: submitted notifies the Cyber Manager; returned (by Cyber Manager or VA POC) and Disputed notify the SMO; passed to endorsement notifies both VA POCs; Overdue notifies the person whose step it is; Register changes (moved, transferred, withdrawn) notify the SMO; Exemption requested notifies both VA POCs and the decision notifies the SMO (wired when those tickets land).
- [ ] VA POC items are two rows sharing a group key; `GET /api/notifications` lists the caller's notifications with read and cleared state; `POST /api/notifications/{id}/read` marks one read; when a VA POC decides an item, the sibling row is cleared with the handler's name.
- [ ] No notification ever contains Finding detail (sentinel test); the Notifications screen lists them for every role.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
