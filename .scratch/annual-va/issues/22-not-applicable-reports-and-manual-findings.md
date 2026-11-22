# 22: Mark a Scan Report not applicable or enter Findings by hand

**Spec:** [Annual VA spec](../spec.md), stories 23.
**Release:** 1
**What to build:** An SMO whose tool cannot export a machine-readable format ticks a box and types Findings by hand, marked Manually Entered; Dependency-Check and Burp can be marked not applicable with a justification, while Secure Configuration is always required (machine-readable or manual).
**Blocked by:** 19 (List Findings and propose a Finding Status)
**Status:** closed
**Effort (orchestrator estimate):** about 4.0 developer days across stories 23 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `PUT /api/submissions/{id}/reports/{type}/mode` sets UPLOAD, NOT_APPLICABLE (Dependency-Check or Burp only, justification required) or MANUAL (justification required); Secure Configuration refuses NOT_APPLICABLE with a 400 problem.
- [x] `POST /api/submissions/{id}/findings/manual` (Scan Report in MANUAL mode) creates a Finding with source MANUAL, title, identifier, package or rule and severity, always shown as Manually Entered.
- [x] A not-applicable justification is reviewed later like a Finding (its review state is PENDING, CONFIRMED or DISPUTED); mode changes and manual entries are audited.
- [x] The Scan Report cards offer the not-applicable toggle and the manual-entry form.
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-18)

**Implemented** (commits `bc0dd3d`, `8264699`, `4fab2df`, `2c6c9bd`):
- **Mode.** `PUT /api/submissions/{id}/reports/{type}/mode` (SMO) sets UPLOAD, NOT_APPLICABLE (Dependency-Check and Burp only) or MANUAL.
  - NOT_APPLICABLE and MANUAL need a justification of at most 4000 characters, cleaned by the shared rule.
  - Secure Configuration with NOT_APPLICABLE, or a justification sent with UPLOAD, is a 400 `invalid-field`.
- **Listing.** `GET /api/submissions/{id}/reports` lists the three Scan Reports with mode, justification and review state, audited as `SCAN_REPORTS_VIEWED` for every role.
- **Manual entry.** `POST /api/submissions/{id}/findings/manual` works only in MANUAL mode.
  - It creates a Finding with source MANUAL, title (200), identifier (200), package or rule (500) and severity, each cleaned to one line.
  - The identity key is `manual|<type>|<identifier>|<package>`, which never matches a parsed key.
  - Refusals: 409 `wrong-report-mode` and 409 `duplicate-finding`.
  - The Findings list now carries `source` and `title`.
- **Review of a justification.** A not-applicable justification starts PENDING. A changed text resets the review to PENDING; the same text keeps it.
- **Audit.** `REPORT_MODE_SET` and `FINDING_ENTERED`, with fixed refusal reasons.
- **Rules (ADR 0030):**
  - The mode is fixed once the Scan Report holds a non-rejected file or any Finding (409 `report-not-empty`); setting the same mode again always works.
  - A machine-readable upload outside UPLOAD is refused with 409 `wrong-report-mode`, audited, and its quarantined bytes are discarded; evidence is allowed in every mode.
  - Every write locks the Scan Report's `report_slot` row: exclusively for a mode change, shared for an upload or a manual entry.
- **Screen.** Each card has a mode select (no "not applicable" for Secure Configuration), a classified justification box, the review state of a not-applicable report, and a manual-entry form. Report-file upload shows only in UPLOAD, and hand-entered Findings show "Manually Entered" with their title.
- **Docs.** V17 adds `report_slot`, `finding.source` and `finding.title`. ADR 0030 records the design; ADRs 0021 and 0027 and the README are amended.

**Decisions made (docs/agents/reviewer-decisions.md, 2026-10-18)**
- The mode stays fixed once the Scan Report holds anything. Deletion is ticket 60 (raised).
- A not-applicable justification is overwritten. Ticket 28 gained a line to decide what text is kept beside a review.
- The manual Finding limits and the exact duplicate rule are accepted.
- Reading the Scan Reports is audited for the owning SMO too, because the justification is free text a person wrote; existing decision of 2026-10-13.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 723 tests, 0 failures, ArchUnit and JaCoCo met. Front end `npm run check`: passed.
- Speed rules approved on 2026-10-17 applied: the build check reused the implementer's gate logs because the fingerprint matched, and 12 selective mutants were run, all killed.
- Reviewer: Must-fix 0, three human decisions (all answered (a)), so no correction pass was needed.
- `/code-reviewer` final gate: PASS on the first run, all seven checks, no Critical or High finding. Report: `artifacts/code-reviewer/22-not-applicable-reports-and-manual-findings-compliance.html`.
- im8 tracked deferrals: as-12 (ticket 51); lm-16 and pm-6 (ticket 52); retention (ticket 49).

All checklist boxes above are ticked.

**Not verified, and open**
- No browser run: signing in needs `../mock-m-sso`, which is not on this machine. The mode form and manual-entry form are checked only by the front-end gate.
- The `report_slot` row lock is held only by the SQL and ADR 0030; no test can show it.
- Reviewer nits not taken:
  - refused `REPORT_MODE_SET` and `FINDING_ENTERED` entries carry the reason but not `report_type`;
  - the manual identity key is built in `ManualFindings` rather than `parser/FindingKeys`;
  - the browser's `maxLength` counts UTF-16 units while the server counts code points, which is stricter and harmless.
- Ticket 25's submit rule must require each Scan Report to be uploaded and parsed, not applicable, or entered by hand. Ticket 28 must cover confirming or disputing a not-applicable justification.
- Medium and Low findings remain open in the compliance report.
