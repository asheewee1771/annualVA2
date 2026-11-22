# 20: Filter chips, select-all, bulk bar and progress bar

**Spec:** [Annual VA spec](../spec.md), stories 36, 37, 38.
**Release:** 1
**What to build:** An SMO works through 100 to 200 Findings with filter chips, tick boxes and select-all, applies one status, one detail and one target date to every selected Finding in one step, and sees how much is left.
**Blocked by:** 19 (List Findings and propose a Finding Status)
**Status:** closed
**Effort (orchestrator estimate):** about 11.6 developer days across stories 36 (4.3*), 37 (4.3*), 38 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/submissions/{id}/findings/bulk-proposals` with a list of Finding ids and one status plus its fields creates one batch and one proposal per Finding carrying the batch id, returning the batch id; Findings not in the submission or not editable are refused as a whole.
- [x] `GET /api/submissions/{id}/readiness` returns total, proposed, unproposed, awaiting review, carried over, target date missed and disputed counts (renamed from "pending" by the 2026-10-17 decision).
- [x] Filter chips Needs attention, All, Disputed and Critical/High work server-side (Carried Over and Target Date Missed arrive in Release 2); select-all selects the filtered set; a sticky bulk bar applies the batch and the progress bar shows the readiness counts with `aria-live`.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-18)

**Implemented** (commits `cca0b9c`, `efbeba6`, `c17b04d`, `3c36e65`): `POST /api/submissions/{id}/findings/bulk-proposals` (SMO) takes `findingIds` (1 to 1000 distinct ids, every one a Finding of this VA Submission, else a 400 `invalid-field` naming `findingIds` with one sentence for missing and other-System ids) plus the single-proposal fields, writes one append-only `bulk_batch` row (V15) and one ordinary proposal per Finding carrying `batch_id`, each becoming current with review state PENDING, and answers 201 `{batchId, count}`; a refused batch writes nothing. Check order: ownership 404, state 409 (audited REFUSED), ids, status fields, memo, membership. Single and bulk proposals share one rules step and one write path (`finding/Proposals`: target, fields, lock in id order, write), and `FindingStore` holds the only proposal SQL. Audit: one `BATCH_PROPOSED` entry per batch plus one `STATUS_PROPOSED` entry per Finding carrying `batch_id` (ids and fixed codes only). `GET /api/submissions/{id}/readiness` returns `total`, `proposed`, `unproposed`, `awaitingReview`, `disputed`, and `carriedOver` and `targetDateMissed` (0 until Release 2); Cyber Manager and VA POC reads are audited as `READINESS_VIEWED`, the owner's are not. The Findings section gains filter chips (Needs attention by default, All, Disputed, Critical/High) mapped to the server-side filters, row and page tick boxes (the page box shows partly ticked), "Select all N that match" (reads ids page by page, stops past 1000), a selection kept across pages, a sticky bulk bar with one status and its classified fields (Apply disabled above 1000), a `<progress>` bar with the counts in an `aria-live` region, a "Bulk-set" marker, a page clamp after a reload, and focus moved to the Findings heading after Apply. ADR 0028 records the design; the README and ticket 49's retention line are amended.

**Decisions made (docs/agents/reviewer-decisions.md, 2026-10-17)**
- A batch holds at most 1000 Findings.
- Readiness names `awaitingReview` and `unproposed`; no field is called `pending` (this ticket's second box is reworded to match).
- A batch writes one `STATUS_PROPOSED` entry per Finding with the batch id, as well as the batch entry.
- Readiness reads follow the existing 2026-10-13 rule for single VA Submission reads (no new decision needed).

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 680 tests, 0 failures, ArchUnit and JaCoCo met, on the final tree. Front end `npm run check`: passed.
- Run with the speed rules the user approved on 2026-10-17: the build check reused the implementer's gate logs because the tree fingerprint matched; after the gate FAIL only the failing check and build were rerun; mutation proofs were selective.
- Reviewer: first pass Must-fix 1 (an empty page past the end after a proposal shrank the filtered list) and three human decisions; after the correction pass Must-fix 0, Human decisions 0.
- `/code-reviewer` final gate, first run FAIL on one High (thermo-nuclear: the single and bulk proposal paths kept two copies of the rules and SQL), fixed by the shared `Proposals` path with no test changed; second round PASS, no Critical or High finding. Round 2 reran thermo-nuclear and build only; code-review, semgrep, im8, react and springboot are carried over from round 1, which reviewed the tree before the refactor. Report: `artifacts/code-reviewer/20-filter-chips-bulk-bar-progress-compliance.html`.
- im8 tracked deferrals: as-12 (ticket 51); lm-16 and pm-6 (ticket 52); retention of the proposal and batch tables (ticket 49).
- 26 selective mutants in isolated copies of `backend/` (12, 4, then 10 on the shared path), all killed. Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- No browser run: signing in needs `../mock-m-sso`, which is not on this machine, so the chips, selection, bulk bar, page clamp and focus change are checked only by the type, build, lint, dependency and fallow gates.
- The five carried-over checks did not see the final `Proposals` refactor; the reviewer and thermo-nuclear did, and every Seam 1 test passed unchanged.
- A single proposal now locks its Finding's row after its checks rather than first (ADR 0028 replaces ADR 0027 item 2); the last commit still wins.
- A batch's proposals are tied to its VA Submission by the application only; lock ordering against deadlocks is not provable through Seam 1.
- Medium items left open in the compliance report: the owner-read audit rule in three places, up to 1000 one-by-one audit inserts per batch, `BulkProposalRequest` copying `ProposalRequest`'s fields, "Select all" downloading full rows to collect ids, the bulk bar and a row's form being two copies of one form, and `FindingsSection.tsx` holding too many parts. A ticked Finding stays selected after it is proposed on its own row.
- Release 2 needs the Carried Over and Target Date Missed counts and chips.
