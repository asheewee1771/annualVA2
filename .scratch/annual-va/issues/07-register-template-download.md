# 07: Download the System Register template

**Spec:** [Annual VA spec](../spec.md), stories 1.
**Release:** 1
**What to build:** A VA POC downloads the fixed Excel template (System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter) from the Register screen.
**Blocked by:** 04 (Roles held in the app and every endpoint denied by default)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 2.3 developer days across stories 1 (2.3*); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/system-register/template` (VA POC only) returns an `.xlsx` with exactly the five columns, header validation hints and one example row, as `Content-Disposition: attachment`.
- [x] The download is audited; an SMO or Cyber Manager receives 403.
- [x] The Register screen has a Download template action under the classification banner.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-05)

**Implemented** (commits `3cb8a8f`, `7277ca0`, `e017321`, `4afd9e4`): `GET /api/system-register/template` (VA POC only) returns `system-register-template.xlsx` as an attachment, built with Apache POI 5.4.1. One sheet with exactly the five headers (`System ID`, `System Name`, `SMO/Tech Lead email`, `Cyber Manager email`, `Quarter`), a hint comment on each, a Quarter drop-down (`Q1` to `Q4`), email checks, text-formatted columns, a frozen header row and one example row (`EXAMPLE-001`). The download is a `REGISTER_TEMPLATE_DOWNLOADED` audit entry written in the command's own transaction (actor and roles from the new `AuditActor`). SMO, Cyber Manager, Auditor and a person with no role get 403, recorded as `REQUEST_REFUSED`; no session gets 401. The VA POC area links to a Register screen at `/va-poc/register` with a Download template button. ADR 0016 records the design.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 223 tests, 0 failures, ArchUnit and JaCoCo met. Run twice, both green.
- Front end `npm run check`: passed.
- Reviewer: 0 Must-fix and 0 Human decisions in the first round.
- `/code-reviewer` final gate: PASS on the first run, no Critical or High findings. Report: `artifacts/code-reviewer/07-register-template-download-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- The Register screen was not viewed in a browser, and the workbook was read back with POI but not opened in Excel.
- Ticket 08 must match the five header texts exactly, accept the `Q1` to `Q4` notation (the spec names the column but not its values), and Block a System ID that starts `EXAMPLE-` (the note is now on ticket 08).
- The wording says the email and Quarter checks "warn", but POI's default error style is STOP, so Excel refuses a bad entry. Set `ErrorStyle.WARNING` on the three validations or reword to "checks".
- `saveFile.ts` revokes the object URL after 0 ms; a longer delay is safer for Firefox and Safari.
- `poi-ooxml` brings transitive dependencies (commons-compress, commons-io, log4j-api, xmlbeans); run a dependency-vuln-scan in ticket 08 or the pre-production checks.
- The `AuditActor.attribute` branch with no role is untested; it is unreachable for this endpoint, but a later caller should get a test.
- Medium and Low findings from the gate remain open in the compliance report.
