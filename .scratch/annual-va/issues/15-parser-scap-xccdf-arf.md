# 15: Secure Configuration parser: SCAP ARF and XCCDF results

**Spec:** [Annual VA spec](../spec.md), stories 20b, 21, 25, 28, 29.
**Release:** 1
**What to build:** A CIS-CAT Pro or OpenSCAP results file (ARF or XCCDF) uploaded as the Secure Configuration Scan Report is read into Findings for failed checks and checks that did not complete, with passed and not-applicable results ignored.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 18.8 developer days across stories 20b (2.0), 21 (1.3), 25 (4.0), 28 (9.5*), 29 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] The parser accepts ARF (`asset-report-collection`) and XCCDF `Benchmark`/`TestResult` (1.1 and 1.2) documents and rejects others as REJECTED_SCHEMA; the ARF size limit and the UI hint to export XCCDF results only when an ARF is too large are in place.
- [x] Results `fail` become Findings keyed `scap|<rule idref>` with one occurrence per host; `error` and `unknown` become Findings marked as checks that did not complete; `pass`, `notapplicable` and `notchecked` are ignored. Unrated checks map to MEDIUM.
- [x] `TestResult@end-time` pre-fills the scan date and `target`/`target-address` the target.
- [x] Sample exports (CIS-CAT XCCDF and OpenSCAP ARF) with provenance notes; hardened XML parsing.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-14)

**Implemented** (commits `7548039`, `0b3b2e3`, `c8b24e7`, `567da35`): a Secure Configuration results file (CIS-CAT Pro or OpenSCAP, ARF or XCCDF 1.1/1.2) that has passed the scan, the type check and the schema check is parsed inside the pipeline's settlement transaction into Findings, the same way ticket 14's Dependency-Check reports are. New `parser.scap.ScapXmlParser` reads results only at `TestResult`, `Benchmark/TestResult` and `asset-report-collection/reports/report/content/TestResult` and skips an ARF's report requests. A Finding is keyed `scap|<rule idref>` with one occurrence per host; `fail` is a Finding; `error` and `unknown` are Findings marked as did-not-complete (V11 adds `finding_occurrence.did_not_complete`; the Findings view computes it as `bool_and` over a Finding's occurrences at read time, so a rule that fails on one host and errors on another is a failed check); `pass`, `notapplicable` and `notchecked` (and `notselected`, `informational`, `fixed` and any unknown result word) are ignored; an unrated check is MEDIUM. The latest `end-time` pre-fills the scan date and the hosts (`target`, else `target-address`, else the TestResult id, up to 50 joined and cut to 200 characters) the target, through ticket 14's audited metadata read. ARF and XCCDF are accepted by the type check and anything else is REJECTED_SCHEMA; the 25 MB (XCCDF) and 50 MB (ARF) limits are ADR 0021's, and the prepare screen shows a hint when a Secure Configuration file is too large. Ticket 14's Dependency-Check XML and JSON parsers were refactored onto new shared code (`parser/FindingsBuilder`, `ParsedReport.Occurrence.merge`, `parser/ReportDates`, a bounded StAX `parser/xml/XmlCursor`), so the merge rule (worse severity; did-not-complete only if both readings did not complete) and the per-file caps each exist once; every string read is capped at 4,000 characters in XML and JSON alike, and the XML walk refuses nesting beyond 100 levels. `UnparseableReportException` now carries a `Reason` (SHAPE or OVER_A_LIMIT); a report over a cap is `REJECTED_TOO_MANY_FINDINGS` with its own accurate sentence (this also changes ticket 14's behaviour, as a document over a cap used to be `REJECTED_SCHEMA`). Hand-written sample exports (`samples/scap/cis-cat-xccdf-results.xml`, `openscap-arf.xml`) carry a provenance note and a README. ADR 0023 records the design, with one-line amendments to ADRs 0021 and 0022.

**Provenance of this work.** The first implementer run on this ticket was cut off by a session interruption and left the whole ticket unreviewed in the working tree; a second run took stock, judged it sound, ran the gates and mutation checks and changed nothing; a reviewer then read the whole tree. The final gate then failed once on a real finding (below) and was fixed in one correction pass.

**Decisions made (docs/agents/reviewer-decisions.md)**
- The report parser caps (20,000 Findings and 50,000 occurrences per file; for Dependency-Check, 20,000 vulnerabilities and 1,000 packages per dependency; 4,000-character strings) are accepted as built for every format.
- A 404 on a content-bearing read is not audited, like every other 404.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 524 tests, 0 failures, ArchUnit and JaCoCo met; run twice in a row from clean on the final tree. `ArchitectureTest` and `ModuleConventionsArchTest` are unmodified. The gate's build check passed it again with Docker up (0 skipped).
- Front end `npm run check`: passed.
- Reviewer: round 1 Must-fix 0 with one Human decision (the caps, answered (a)); round 2 Must-fix 0.
- Mutation checks in an isolated copy (main tree never mutated): moving `findings.record` out of the settlement transaction fails the SCAP atomicity test; `bool_and` to `bool_or` fails four tests; changing the did-not-complete merge or the unrated default fails seven; `FindingsBuilder` not enforcing the caps fails the cap tests (this exposed that the older SCAP cap test had been passing only through a per-host bound, so a test across hosts was added); `Occurrence.merge` taking the better severity fails two SCAP tests.
- `/code-reviewer` final gate: the first run FAILED on one thermo-nuclear High (the merge rule duplicated in `ScapXmlParser.Verdict` and `FindingsBuilder`, the per-file cap enforced in five places, a misleading message for the 1,000-packages bound, a boolean-flagged exception). One correction pass closed it; the second complete run is PASS with warnings (build PASS, semgrep PASS from the official Docker image, react PASS; thermo-nuclear, code-review, im8 and springboot WARN), with no Critical or High finding. Report: `artifacts/code-reviewer/15-parser-scap-xccdf-arf-compliance.html`.
- im8 tracked deferrals: as-12 production scanner adapter and file store (ticket 51); lm-16 meters (ticket 52).
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- The samples are hand-written reconstructions; real CIS-CAT and OpenSCAP exports are untested until ticket 58, which should be treated as a gate for this parser before release.
- No browser run of the front-end change (one status label and the too-large hint).
- Severity comes from `rule-result@severity` only (unrated is MEDIUM); the Benchmark's `Rule@severity` is not read, and CIS-CAT writes `unknown` there anyway, so a rule rated high in the Benchmark but unrated on its result reads MEDIUM. A scan reaches the occurrence cap at about 250 hosts each failing 200 rules; the byte limits usually bind first.
- No explicit surefire heap is set, and the 49 MB ARF test needs about 512 MB (it failed at 320 MB and passed at 512 MB), so a small CI runner would fail it. Add `-Xmx1g` to the surefire `argLine` (kept compatible with JaCoCo's `@{argLine}`). Ticket 14's all-thread allocation test in `DependencyCheckLimitsTest` is also brittle.
- `XmlParserDepthTest` drives the parsers directly, which departs from the Seam 1 convention, because the pipeline's schema check never lets such a file through; record the exception in `reviewer-decisions.md` or ADR 0023, or drop the guard.
- `catch (RuntimeException)` in the three parsers turns a parser bug into a bad-file rejection with no trace; log the error type only. A settlement that keeps failing is retried every pass without counting as an attempt (carried over from ticket 14; ticket 51 is the backstop).
- Remaining structural debt: the Dependency-Check XML parser's nine-case switch, duplicated XML scaffolding, severity written as a second hand-synchronised copy in the list SQL, Finding identity carried redundantly, and `ScapXmlParser.readTestResult` doing too much. Still open from ticket 14: no aggregate Findings cap per VA Submission, and uploading the same file twice doubles occurrence counts until ticket 21.
- Environment: the implementer renamed `%LOCALAPPDATA%\Docker\run` to `run.stale-20261014` to get Docker Desktop to start (a stale socket made it crash). Nothing was deleted and it is reversible; the stale directory can be removed after a reboot.
- Medium and Low findings remain open in the compliance report.
