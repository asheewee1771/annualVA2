# 14: Dependency-Check parser (XML and JSON)

**Spec:** [Annual VA spec](../spec.md), stories 20a, 21, 25, 28.
**Release:** 1
**What to build:** An OWASP Dependency-Check XML or JSON export uploaded as the Dependency-Check Scan Report is read into Findings with their severity and occurrences, and the scan date and project name are pre-filled for the SMO to confirm.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** closed
**Effort (orchestrator estimate):** about 19.8 developer days across stories 20a (5.0*), 21 (1.3), 25 (4.0), 28 (9.5*); `*` marks the critical path. See `reports/dev-report.md`.

- [x] The parser accepts the Dependency-Check XML (`analysis` root in the DependencyCheck namespace) and JSON (`reportSchema`, `scanInfo`, `dependencies[]`) shapes and rejects anything else as REJECTED_SCHEMA.
- [x] Each vulnerability becomes one Finding keyed `dc|<CVE or GHSA>|<package purl or file name>` scoped to the System, with one occurrence per dependency; severity from CVSS v3 then v2, unrated to HIGH, with the five-level scale.
- [x] `projectInfo/reportDate` pre-fills the scan date and the project name the target; `GET /api/submissions/{id}/findings` lists the Findings with report type, identifier, package, severity, source file and occurrence count.
- [x] Sample exports carry a provenance note (schema-faithful reconstructions from a throwaway project, to be regenerated from a real scan before release); XML parsing uses hardened factories (no DTD, no external entities).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-14)

**Implemented** (commits `2c3d914`, `1ace627`, `feaa166`, `8c20ec3`): a Dependency-Check report that has passed the scan, the type check and the schema check is now parsed inside the pipeline's settlement transaction, so a file reaches `PARSED` together with its Findings or not at all. New `finding` module (`Findings`, `FindingStore`, `FindingController`, `FindingView`, `ReportType` moved here) and `parser` / `parser.dependencycheck` packages: a streaming Jackson JSON parser and a StAX XML parser (readers made only in `parser.xml.SecureXml`), both accepting the Dependency-Check XML and JSON shapes and rejecting anything else as `REJECTED_SCHEMA` with the usual plain message. A Finding is keyed `dc|<CVE or GHSA>|<purl or file name>` with one occurrence per dependency; severity is CVSS v3, then v2, an unrated vulnerability is HIGH, over five levels; Findings merge across the files of one VA Submission taking the worst severity and never across Systems. `GET /api/submissions/{id}/findings` lists them paged, most severe first, readable by whoever may see the System (404 for anyone else, 400 for a page or size out of range) and audited as `FINDINGS_VIEWED` on every read for every role; the scan date (from `reportDate`) and target (the project name) pre-fill is served from its own `GET /api/submissions/{id}/files/{fileId}/metadata`, audited for every role as `FILE_METADATA_VIEWED` and 404 until the file has passed every stage, so the polled status and list views stay free of report content (per the 2026-10-13 decisions). Caps of 20,000 Findings and 50,000 occurrences per file, and per-dependency caps, bound hostile input; inserts are chunked at 5,000 rows. V10 adds `finding` and `finding_occurrence`. The samples carry a provenance note. ADR 0022 records the design; a one-line amendment is appended to ADR 0021.

**Provenance of this work.** The first implementer run on this ticket was cut off by a session interruption and left unreviewed production code; a second run took stock, found and fixed a real defect in it (Jackson's trailing-token check made every JSON report fail while the inherited tests never parsed a JSON file with content), wrote all the tests, and finished. The reviewer then saw the whole tree, and a hardening pass followed (below).

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 488 tests, 0 failures, ArchUnit and JaCoCo met; run twice in a row from clean on the final tree. The gate's build check passed it again.
- Front end `npm run check`: passed.
- Reviewer: Must-fix 0, no Human decision. Two Should-fix items were taken up before the final gate: the fault-injection test was vacuous (its trigger fired before the Findings were written), so a trigger after the last write now proves the file stays `SCAN_SUBMITTED` with zero Findings and a retry gives exactly one set (checked by mutation in an isolated copy: moving `Findings.record` out of the transaction fails it); and memory on hostile input was unbounded, so the Findings and occurrence caps, a streaming JSON parser and chunked inserts were added, with Seam 1 tests at, over and far over the caps and a heap-allocation test for a 16 MB unused field.
- `/code-reviewer` final gate: PASS, all seven checks ran, no Critical or High finding (build PASS, semgrep PASS from the official Docker image, react PASS; thermo-nuclear, code-review, im8 and springboot WARN). Report: `artifacts/code-reviewer/14-parser-dependency-check-compliance.html`.
- im8 tracked deferrals: as-12 production scanner adapter and file store (ticket 51); lm-16 meters (ticket 52); pm-6 OpenAPI entries for the two new endpoints (ticket 52).
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Needs a human decision**
- The Findings caps (20,000 Findings and 50,000 occurrences per file; 20,000 vulnerabilities and 1,000 packages per dependency) are a new limit. ADR 0022 item 9 calls it a proposal a human confirms, but its status reads accepted: confirm or change the numbers. A report over the cap is rejected as `REJECTED_SCHEMA`, whose message ("not in the expected format") is misleading for a report that is merely too large.
- A 404 on a content-bearing read (the Findings list, the metadata read) is not audited, as with every other 404 (ADRs 0013 and 0014). Say if that should change.

**Not verified, and open**
- No browser run of the screen; the front end change is one status label and the Download rule.
- The parser has only met hand-written samples (provenance noted). Regenerating real-scan samples (ticket 58) should be treated as a gate for this parser before release.
- Nothing bounds Findings per VA Submission: up to 20 files can hold 400,000 Findings and 1,000,000 occurrences, the list sorts the whole submission with no supporting index and `page` has no upper bound. It needs an aggregate cap, or an index and keyset paging.
- A settlement that keeps failing is retried every pass with up to 25 MB reprocessed and an ERROR line each time, with the attempt counter untouched (accepted in ADR 0022; ticket 51 is the backstop).
- XML leaf text is read whole by `getElementText()` and copied several times before any length check; there is no string limit as in JSON. Heap at maximum key length is unmeasured.
- The allocation-based heap test in `DependencyCheckLimitsTest` is brittle (an all-thread sum with about 130 MB of headroom) and takes about 70 seconds.
- Structural debt for tickets 15 to 17 to avoid copying: a Finding's identity stored three to four times, a denormalised severity with a magic SQL array, a switch-heavy XML parser, a repeated JSON walk and schema pass, parallel `Outcome` and `Settlement` records, a nullable parser slot in `ReportFormat`, and `ReportType` living in `finding` with a circular join to `report_file`. `FindingStore` also joins the ingestion-owned `report_file` table directly, which ArchUnit cannot see.
- Uploading the same export twice doubles every occurrence count (ticket 21 owns replacement). `finding_occurrence.report_file_id` has no `ON DELETE` action (tickets 21 and 49).
- Medium and Low findings remain open in the compliance report.
