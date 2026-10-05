# 22: Mark a Scan Report not applicable or enter Findings by hand

**Spec:** [Annual VA spec](../spec.md), stories 23.
**Release:** 1
**What to build:** An SMO whose tool cannot export a machine-readable format ticks a box and types Findings by hand, marked Manually Entered; Dependency-Check and Burp can be marked not applicable with a justification, while Secure Configuration is always required (machine-readable or manual).
**Blocked by:** 19 (List Findings and propose a Finding Status)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 4.0 developer days across stories 23 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `PUT /api/submissions/{id}/reports/{type}/mode` sets UPLOAD, NOT_APPLICABLE (Dependency-Check or Burp only, justification required) or MANUAL (justification required); Secure Configuration refuses NOT_APPLICABLE with a 400 problem.
- [ ] `POST /api/submissions/{id}/findings/manual` (Scan Report in MANUAL mode) creates a Finding with source MANUAL, title, identifier, package or rule and severity, always shown as Manually Entered.
- [ ] A not-applicable justification is reviewed later like a Finding (its review state is PENDING, CONFIRMED or DISPUTED); mode changes and manual entries are audited.
- [ ] The Scan Report cards offer the not-applicable toggle and the manual-entry form.
- [ ] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
