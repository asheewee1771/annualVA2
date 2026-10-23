# 23: Confirm scan date and target; enforce the age rules

**Spec:** [Annual VA spec](../spec.md), stories 25, 26.
**Release:** 1
**What to build:** The scan date and target pre-filled from each file are shown for the SMO to confirm or correct; a future-dated file is rejected at upload, and a file older than 90 days at submission time blocks submitting with a message naming that file.
**Blocked by:** 14 (Dependency-Check parser (XML and JSON))
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.3 developer days across stories 25 (4.0), 26 (2.3); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `PUT /api/submissions/{id}/files/{fileId}/metadata` lets the SMO confirm or edit scan date and target; an edited target is flagged, never blocked; Burp dates are shown as export time only.
- [x] A file whose scan date is after today's business date is rejected as REJECTED_FUTURE_DATED during the pipeline; the pipeline order test now covers parse, pre-fill and age after the schema check.
- [x] The readiness check reports every file older than 90 days at today's business date by name; the age is evaluated against the controllable clock so tests move the clock rather than rewriting samples.
- [x] The Scan Report card shows the scan date and the confirmed target, both editable, with an edited target marked.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-23)

**Implemented** (commits `e9f3d7e`, `3d512b4`, `eef4824`, `cc14292`; `6b4ab42` ignores the do-work verify cache):
- **Confirm scan date and target.** `PUT /api/submissions/{id}/files/{fileId}/metadata` (the SMO/Tech Lead, in Draft or Returned for Rework, for a parsed report file) takes `scanDate` and `target` and answers with the metadata view.
  - A scan date or target that differs from the file's is flagged (`scanDateEdited`, `targetEdited`) and never refused. For a Burp export the file's own day is its export day, so one rule covers every format.
  - A file a rescan replaced is 409 `file-superseded`.
  - `FILE_METADATA_CONFIRMED` carries `file_id` and the booleans `date_changed` and `target_changed`, never the values.
  - `ScanFacts` owns the scan-date rules: the date judged, export time only, and the two flags.
- **Future-dated files.** The age check is the pipeline's last stage. A report dated after today's business date in Singapore is `REJECTED_FUTURE_DATED` after the parse, and no Findings are kept. The pipeline order test covers parse, pre-fill and age after the schema check.
- **Readiness.** `GET /api/submissions/{id}/readiness` names the files more than 90 days old (`tooOld`) and those with no scan date to judge (`undated`, including a Burp file with only an export time), judging the confirmed date first. The clock is the controllable `Clock` bean; tests move it.
- **Screen.** Each parsed report file on the Scan Report card shows the scan date and target, pre-filled and editable, with "Scan date edited" and "Target edited" marks. Files too old or undated are named under the progress bar.
- **Docs.** V18 adds the confirmed scan date and target and the new detail keys. ADR 0031 records the design, ADRs 0021, 0025 and 0028 are amended, and CONTEXT.md gains "Scan date".

**Decision made (docs/agents/reviewer-decisions.md, 2026-10-21)**
- A changed scan date is flagged, not blocked, and the confirmed date is still judged (option (b)). The audit entry records `date_changed` and `target_changed`. The readiness list is unchanged, and the review screens (ticket 28 onwards) read the flag.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 748 tests, 0 failures, ArchUnit and JaCoCo met. Front end `npm run check`: passed.
- Reviewer: Must-fix 0, no human decisions.
- `/code-reviewer` round 1: FAIL on three blockers: a changed scan date was not flagged (code-review Spec P1); the scan-date rules were duplicated (thermo-nuclear High); raw bidi characters sat in a test literal (semgrep P0). After the decision above and two correction passes, round 2 is a PASS: build PASS; thermo-nuclear, code-review, semgrep, im8, react and springboot WARN; no Critical or High finding. Report: `artifacts/code-reviewer/23-scan-date-target-and-age-rules-compliance.html`.
- Speed rules approved on 2026-10-17 applied: the build check reused the implementer's verify log because the fingerprint matched. Round 2 reran only the failing checks plus build; im8, react and springboot carried over from round 1.

All checklist boxes above are ticked.

**Not verified, and open**
- The selective mutation gate was skipped at the user's request after a run was interrupted. No mutants were reported, and the tree matched the last clean build afterwards.
- im8, react and springboot were not rerun after the round-2 change (the `scanDateEdited` flag, the audit booleans and the card mark).
- No browser run. The scan-date form is checked only by the front-end gate.
- Reviewer low notes not taken:
  - after saving, the form remounts and focus is lost, and nothing is announced;
  - the form reloads `/metadata` after the PUT, which writes a redundant `FILE_METADATA_VIEWED` entry;
  - the Burp date label wording contradicts its pre-filled export day;
  - the tests use inline fully qualified names.
- Thermo-nuclear Mediums left open: request-field validation is copied a third time in `MetadataFields`, and the scan-date block sits inside the shared `FileSection` in `ScanReportCard.tsx`.
- Ticket 25 (submit) must refuse `tooOld` and `undated` files, and should take the submission row lock or re-check readiness under it, because a confirmation can race a submit.
- Medium and Low findings remain open in the compliance report.
