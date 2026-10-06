# 10: Apply a Register preview; SMO and Cyber Manager roles follow the Register

**Spec:** [Annual VA spec](../spec.md), stories 2, 3, 4, 73, 79.
**Release:** 1
**What to build:** A VA POC applies a confirmed preview with a fresh sign-in. Systems are added or updated by System ID, missing ones are retired, and the SMO and Cyber Manager grants are created from the Register so that a former SMO loses access at once.
**Blocked by:** 08 (Upload the System Register and preview every change), 09 (Step-up: a fresh sign-in before sensitive actions), 05 (Append-only audit log with refused-request logging)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 30.5 developer days across stories 2 (10.0*), 3 (8.5), 4 (5.0), 73 (5.0), 79 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/system-register/previews/{id}/apply` (VA POC, step-up) applies ADD, UPDATE and RETIRE rows in one transaction; a retired System keeps its history and is never deleted; re-applying an expired or already applied preview is refused with 409.
- [x] For every System the apply upserts an SMO grant and a Cyber Manager grant with source REGISTER scoped to that System, by email where the person has not signed in yet; grants for people no longer named are revoked in the same transaction and their next request is refused.
- [x] The apply is audited with counts, plus one entry per retired System and per role granted or revoked; the audit never records emails in free text.
- [x] `GET /api/systems` (VA POC all; SMO and Cyber Manager only their Systems) lists Systems with SMO, Cyber Manager, Quarter, retired flag and the Unlinked SMO flag.
- [x] The Register screen's Apply needs step-up and then shows the updated Register table.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-06)

**Implemented** (commits `88b0ed9`, `134d638`, `aa06cad`, `cf1fd17`): `POST /api/system-register/previews/{id}/apply` (VA POC only, step-up intent `register-apply`) applies ADD, UPDATE and RETIRE rows in one transaction under a Postgres advisory lock. A retired System keeps its row and history and is never deleted; one listed again is reinstated with the same id. A second apply returns 409 `preview-applied`, an expired preview 409 `preview-expired`, a preview with a blocked row 409 `preview-blocked`, and a Register that changed since the preview was made 409 `register-changed`; an unknown id is 404. System IDs are matched by the database (citext). Every active System gets an SMO and a Cyber Manager grant with source REGISTER scoped to it (the only writer is `RegisterRoles`), by email where the person has not signed in and attached at first sign-in; anyone no longer named, and everyone on a retired System, is revoked in the same transaction and refused on their next request; re-applying the same Register changes no grant; a configured VA POC named as an SMO keeps VA_POC. The apply is audited (`REGISTER_APPLIED` with counts, one `REGISTER_SYSTEM_RETIRED` per retirement, one `ROLE_GRANTED` or `ROLE_REVOKED` per grant) with no email, name or System ID. `GET /api/systems` gives a VA POC every System and an SMO or Cyber Manager only their own, through `AccessGuard.scope(VIEW)`, with the retired flag and the Unlinked SMO flag recomputed on each read (null for non-VA-POCs). V7 adds the `role_grant.system_id` foreign key. The Register screen has an Apply button, reopens the same preview after the step-up dialog and shows the updated Register table. ADR 0018 records the design.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 307 tests, 0 failures, ArchUnit and JaCoCo met. Run three times by the implementer (the first run had one failing test of its own, fixed) and twice more by the gate's build check.
- Front end `npm run check`: passed.
- Reviewer: 0 Must-fix; one Human decision, answered below.
- `/code-reviewer` final gate: PASS on the first run with no Critical or High finding. Report: `artifacts/code-reviewer/10-register-apply-and-roles-from-register-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Decision made (docs/agents/reviewer-decisions.md)**
- Reading the Register list (`GET /api/systems`) is not audited; it holds no report or Finding data. Revisit if the list begins to carry report content.

**Not verified, and open**
- The advisory lock under contention and a failure after writes have started are untested: two applies run sequentially in the tests, so deleting the lock would leave them green. Add a Seam 1 test that holds `pg_advisory_xact_lock(hashtext('annualva.register-apply'))` on a second connection.
- The front end step-up reopen flow was not viewed in a browser. `leftForSignIn` reads the saved draft at module load, so an abandoned step-up within 15 minutes can reopen the preview on the next load of the Register screen, and the read consumes any other intent's draft.
- A database failure inside the apply is not wrapped the way the upload path wraps it (`PreviewNotStoredException`), so a constraint failure there could log a System ID or email; unreachable today given the lock and the blocked rows.
- The Register workbook still has no malware scan (IM8 as-12, owned by ticket 13, accepted by the user). Expired and applied previews are not purged (ticket 49). The preview's effects on in-flight VA Submissions are ticket 43; none exist yet.
- Lapsed and suspended grants are not revived by the apply (tickets 46 and 47). `RoleGrantStore.grant` returns an existing live grant of any source on a key collision, so a later non-REGISTER SMO or Cyber Manager grant would be counted as granted by `follow`.
- The Register table is not paged. `added_on` is stored as the Singapore business date but not exposed or asserted.
- Medium and Low findings remain open in the compliance report.
