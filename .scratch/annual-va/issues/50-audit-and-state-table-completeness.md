# 50: Completeness: every audited action and every row of the state table

**Spec:** [Annual VA spec](../spec.md), stories 84, state table, Testing Decisions.
**Release:** 1
**What to build:** Two parameterised Seam 1 suites close the Testing Decisions: every action named in the audit story writes its entry, and every state of the VA Submission answers every command exactly as the state table says.
**Blocked by:** 40 (Request, freeze and decide an Exemption), 41 (VA Cycle lifecycle: open, overlap, close), 42 (Conditional Exemption: record the VA, confirm it, or lapse), 43 (Register preview effects on in-flight work: withdraw, and block the rest), 44 (Management summary export (Excel), watermarked, no vulnerability detail), 47 (Role-holder review, suspension and identity-provider disablement), 49 (Retention: disposal list, approval and hard delete)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 7.0 developer days across stories 84 (7.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A parameterised test walks every audited action (view, download, upload, status change, confirmation, Dispute, return, endorsement, Exemption decision, Register change, role change, export, disposal) and asserts its entry with actor, role, outcome and ids and without detail text.
- [ ] A parameterised test drives every state of the state table with every command: allowed transitions succeed; disallowed ones answer 409 `invalid-transition`; Endorsed, Exempted, Withdrawn and Not Endorsed are read-only; Exemption Requested is frozen; the Overdue flag is set exactly where the table says.
- [ ] A permission-matrix test covers each role against each action (200, 401, 403, 404) and the step-up list (stale and fresh), per the implementation plan section 6; every refusal row also asserts its audit entry.

## Comments
