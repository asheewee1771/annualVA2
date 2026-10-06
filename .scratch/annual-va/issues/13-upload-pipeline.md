# 13: Scan Report upload pipeline: quarantine, scan, check, store

**Spec:** [Annual VA spec](../spec.md), stories 20 (pipeline), 24, 27, 78.
**Release:** 1
**What to build:** An SMO uploads one or more files for a Scan Report type. Each file is quarantined, malware-scanned, type-checked and schema-checked before anything reads it; a bad file is rejected with a plain message, a scan that keeps timing out is rejected after two retries, and nobody can view a file until it is clean.
**Blocked by:** 12 (Start a VA Submission)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 18.5 developer days across stories 20 (11.5*), 24 (2.0), 27 (2.0), 78 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/submissions/{id}/files` (SMO, Draft or Returned for Rework, multipart with report type and kind) quarantines the bytes with a SHA-256 and returns 202 with a file id in state QUARANTINED; the stages then run in the fixed order quarantine, malware scan, type allowlist, schema check, and a test proves that order (parse, pre-fill and age are appended by the parser and metadata tickets, which extend the test).
- [ ] Scanning goes through the malware scanner boundary (submit, check, fetch clean); at most 10 files are in flight at once, an attempt times out after the configured period and is re-submitted, and after three attempts the file is REJECTED_SCAN_TIMEOUT with the message that the upload was rejected and should be uploaded again. A blocked verdict gives REJECTED_MALWARE and the quarantined bytes are discarded (malware is never retained; recorded as a decision beside the retention rules).
- [ ] Type allowlist by declared report type with root-element or JSON-shape sniffing and the per-type size limits (ADR); a disallowed or oversized file is rejected before parsing; the schema check rejects unreadable files; machine-readable kinds hand over to the parsers that arrive in the next four tickets.
- [ ] `GET /api/submissions/{id}/files/{fileId}` reports the pipeline status and reject code; `GET .../files/{fileId}/content` serves bytes as an attachment only when the file is clean and the caller may see the System, otherwise 404; every view and download is audited, and a direct download by someone who is not allowed to see the System is refused with 404 (Testing Decisions).
- [ ] Errors never echo file content; log lines carry only file ids, statuses and attempt counts (never presigned URLs or file names).
- [ ] The Seam 1 harness gains a fake scanner (clean-unchanged, sanitised, blocked, never-finishes) and an in-memory file store; the Scan Report cards on the prepare screen show per-file status and the rejection message.
- [ ] The System Register workbook upload (`POST /api/system-register/uploads`, ticket 08) goes through the same quarantine and malware scan before `RegisterSheetReader` reads it (spec story 24, IM8 as-12). Decide whether this scan is synchronous for the small Register file or the upload gains a pending state (today it answers 201), and update ADR 0017 and the Register tests accordingly.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Note from ticket 08 (2026-10-06): the Register workbook must be scanned too

Spec story 24 ("every upload is malware-scanned ... before it's read") and IM8 as-12 ("Uploads are malware-scanned before they are read") cover the System Register upload as well as Scan Reports. Ticket 08 parses the Register `.xlsx` with Apache POI **without** a malware scan, because the `MalwareScanner` boundary does not exist yet (this ticket builds it). It is a tracked deferral (docs/agents/reviewer-decisions.md, "IM8 controls owned by a later ticket"), owned by this ticket: when the quarantine and scan stages exist, route `POST /api/system-register/uploads` through them before `RegisterSheetReader` reads the bytes. Open design point for whoever builds it: the scan is asynchronous in the pipeline (202 and polling), whereas the Register upload answers `201` with the preview today; either scan synchronously for this small file or give the upload a pending state, and update ADR 0017. Mitigations in place until then: bytes-sniffed type, 2 MB cap, 200-part and 20 MB unpack caps, no macros, formulas never evaluated, DOCTYPE refused, the file is never stored or served, and only the two VA POCs (MFA sign-in) can upload.
