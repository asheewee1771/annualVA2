# 21: Replace a Scan Report file without losing work

**Spec:** [Annual VA spec](../spec.md), stories 39.
**Release:** 1
**What to build:** An SMO replaces one file of a Scan Report with a rescan. Only that file's occurrences are compared: matching Findings keep their status and history, new ones are added, and a Finding the rescan no longer reports anywhere becomes Remediated with the rescan as evidence.
**Blocked by:** 19 (List Findings and propose a Finding Status)
**Status:** closed
**Effort (orchestrator estimate):** about 10.5 developer days across stories 39 (10.5); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/submissions/{id}/files/{fileId}/replace` runs the new file through the whole pipeline and, once parsed, compares identity keys against the old file's occurrences only.
- [x] Kept keys swap occurrences and keep proposals and reviews; keys absent from the new file lose those occurrences, and a Finding left with no occurrence gets a new proposal Remediated with evidence naming the rescan file and date, proposed as RESCAN, review state PENDING; new keys create new Findings.
- [x] The old file is marked superseded (kept until the submission is Endorsed, then its bytes are listed for disposal while its record stays until Cycle plus 3 years, decision 2026-10-18), the replacement is audited, and the Scan Report card offers Replace per file.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-18)

**Implemented** (commits `f3b5fab`, `8e38ca6`, `d259f85`, `0ea81bd`): `POST /api/submissions/{id}/files/{fileId}/replace` (SMO, multipart) runs the rescan through the whole pipeline. Once it is parsed, one settlement transaction compares identity keys against the old file's occurrences only:
- kept keys keep their Finding, proposals and review, with the old file's places swapped for the rescan's;
- keys the rescan no longer reports lose the old file's places, and a Finding then reported by no file gets an automatic Remediated proposal (`proposed_as=RESCAN`, review PENDING) whose evidence names the rescan with its scan date and upload date and points at the replaced file ("Last reported in <name>" with a download), unless its current proposal is already Remediated;
- new keys become new Findings;
- a Finding whose current proposal is RESCAN and is reported again by any later file loses that proposal as current (history kept), audited as `PROPOSAL_WITHDRAWN`;
- severity is always re-rated from the Finding's current places.
Settlements of one VA Submission are serialised by a transaction advisory lock, then lock every Finding they touch once in id order. Proposals of every kind share one write path and audit builder (`Proposals.write` with a `Proposer`), and every `STATUS_PROPOSED` entry carries `proposed_as`. The old file is superseded (`superseded_by`, `superseded_at`), stays PARSED and downloadable, and no longer counts against the 20-file cap. A rejected rescan changes nothing. Refusals: 404 (another SMO or a foreign file), 403 (Cyber Manager, VA POC), 409 `invalid-transition`, `file-not-replaceable` (not PARSED, superseded or evidence), `replace-in-progress`, 400 `file-empty`. Automatic entries name the uploader as actor. The Scan Report card offers Replace per file with a classified file input. ADR 0029 records the design; ADRs 0021, 0022, 0027 and 0028 and the README are amended.

**Decisions made (docs/agents/reviewer-decisions.md, 2026-10-18)**
- The evidence names the report's scan date and the upload date.
- The uploader is the proposer and the audit actor of automatic entries.
- Old occurrences are deleted, and the rescan proposal names and links the replaced file.
- Supporting evidence cannot be replaced.
- A re-reported RESCAN Finding loses the proposal as current and is re-rated.
- One id-ordered lock over every Finding a settlement touches.
- No rescan proposal over an already-Remediated one.
- An early disposal of a superseded file deletes its bytes and keeps its record (ticket 49 line).
- A VA Submission cannot leave Draft or Returned for Rework while any of its files is scanning; the settlement stays as built (lines added to tickets 25, 40 and 43).

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 697 tests, 0 failures, ArchUnit and JaCoCo met, on the final tree. Front end `npm run check`: passed.
- Run with the speed rules approved on 2026-10-17: the build check reused the implementer's gate logs (fingerprint matched); gate rounds after a FAIL reran only thermo-nuclear and build; mutation proofs were selective (35 mutants over five runs, all killed; no mutant can prove the locks, as Seam 1 runs one request at a time).
- Reviewer: round 1 Must-fix 1 (a wrong deadlock claim) and seven human decisions; round 2 Must-fix 1 (an unaudited withdrawal) and one decision; round 3 Must-fix 0, Human decisions 0.
- `/code-reviewer` final gate: round 1 FAIL on two thermo-nuclear Highs (rescan proposals kept their own drifting copy of the proposal write and audit; severity kept a stale running maximum); round 2 FAIL on one new High introduced by that fix (concurrent settlements of one submission could miss a key's lock and re-rate); round 3 PASS, no Critical or High finding. Rounds 2 and 3 reran thermo-nuclear and build only; code-review, semgrep, im8, react and springboot are carried over from round 1, which reviewed the tree before those corrections. Report: `artifacts/code-reviewer/21-replace-scan-report-file-compliance.html`.
- im8 tracked deferrals: as-12 (ticket 51); lm-16 and pm-6 (ticket 52); retention of superseded files and proposals (ticket 49).

All checklist boxes above are ticked.

**Not verified, and open**
- No browser run: signing in needs `../mock-m-sso`, which is not on this machine; the Replace form and the provenance labels are checked only by the front-end gate.
- The advisory lock and the id-ordered row locks are held only by the SQL and ADR 0029; no test can show them.
- The five carried-over checks did not see the three corrections; the reviewer, thermo-nuclear and all 697 tests did.
- The refusal of submit, exemption request and withdraw while a file is scanning is owed by tickets 25, 40 and 43.
- Medium and Low items left open in the compliance report: the 20-file cap can overshoot while rescans are in flight; the Cyber Manager's view of the replaced-file link has no owning ticket (29) or test; `= ANY` over up to 20,000 hashes; one audit insert per Finding under the locks; `ReplaceForm` duplicates `UploadForm`; `FindingStore` keeps growing; the nullable mode in `keep`, the `Proposer` shape and `ScanPoller` owning rescan semantics; focus when the Replace form vanishes on a poll.
