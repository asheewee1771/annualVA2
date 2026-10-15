# 17: Burp Suite parser: XML issue export

**Spec:** [Annual VA spec](../spec.md), stories 20d, 21, 25, 28, 30.
**Release:** 1
**What to build:** A Burp Suite XML issue export uploaded as the Burp Scan Report is read into Findings with severity and confidence, so that Tentative issues can later be marked False Positive with a reason.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 19.8 developer days across stories 20d (2.0), 21 (1.3), 25 (4.0), 28 (9.5*), 30 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] The parser accepts the Burp `issues` export, allowing only Burp's internal DTD subset as Burp writes it (a DOCTYPE is skipped and never obeyed for every other format; DTDs and entities are off for all), and rejects others; the UI hints to export without request and response bodies when the file exceeds the limit.
- [x] Each issue becomes a Finding keyed `burp|<type id>|<host>|<normalised path>` with confidence stored and shown; Information maps to INFORMATIONAL.
- [x] `issues@exportTime` pre-fills the scan date flagged as export time only; the hosts pre-fill the target.
- [x] Sample export with a provenance note.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-15)

**Implemented** (commits `8926af3`, `4e90804`, `b4fc56a`, `8cdd0f8`): a Burp Suite XML issue export uploaded as the Burp Scan Report that has passed the scan, the type check and the schema check is parsed inside the pipeline's settlement transaction into Findings, as for tickets 14 to 16; every report format now has a parser, so `ReportFormat.parser()` is no longer nullable and no file rests at `SCHEMA_OK` (it is a pass-through stage). New `parser.burp.BurpXmlParser` streams through `XmlCursor` and reads only `issues/issue/{type,name,host,path,location,severity,confidence}`, each bounded; `requestresponse` and everything else is skipped without being built, kept or logged. A Finding is keyed `burp|<type id>|<host lower-cased>|<normalised path>` (the path loses its query, fragment and `;jsessionid`-style parameters, `//` collapses, a trailing slash goes, a leading one is added, percent-escape hex is upper-cased, an empty path is `/`); one occurrence per distinct `location`, falling back to the path. Severity: High, Medium and Low as written, `Information` is INFORMATIONAL, any case, missing or unknown is MEDIUM. Confidence (Certain, Firm, Tentative) is stored per occurrence in new column `finding_occurrence.confidence` (V12, check constraint; a missing or unknown word is NULL, never Tentative and never Certain), a Finding's confidence is the most sure of its places and is computed when the list reads, returned as the new `confidence` field of `GET /api/submissions/{id}/findings`; the same place read twice takes the more sure word. `issues@exportTime` pre-fills the scan date (the weekday must match; a zone abbreviation is resolved by Java's own table, so `CST` and `IST` can be hours out) and the metadata read flags it with `scanDateIsExportTime` (true for Burp only, derived from the format, nothing stored); the distinct hosts pre-fill the target. DOCTYPE: the reader never loads DTDs or external entities; a byte-level check of the first 32 KB admits only an optional BOM and XML declaration, comments, an optional `<!DOCTYPE issues ...>` with no external id and only ELEMENT/ATTLIST declarations, then the `issues` root; anything else (an ENTITY or NOTATION declaration, a processing instruction, a second or hidden DOCTYPE, an external id, a UTF-16/32 export) is refused. Before the parser, the Dependency-Check, SCAP and Nessus parsers were refactored, behaviour unchanged, onto a shared `XmlCursor.read` (the one leak-safe parse wrapper), `nextChild()` and `below()`, `parser.ScanReading`, severity policy in `Severity` (`ofScap`, `ofNessus`, `ofBurp`) and `parser.FindingKeys`. A hand-written sample with a provenance note and README is under `samples/burp/`. One front-end string (the Burp too-large hint) and one corrected comment. ADR 0025 records the design, with one-line amendments to ADRs 0021 to 0024, and `CONTEXT.md` gains Confidence and Export time.

**Decision made (docs/agents/reviewer-decisions.md)**
- A DOCTYPE is skipped and never obeyed for the Dependency-Check, SCAP and Nessus parsers, and only Burp's internal subset is accepted, as Burp writes it. This amends the plan line and this ticket's wording ("every other format forbids DOCTYPE"), which tickets 14 to 16 had never built.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 589 tests, 0 failures, 0 skipped (33 new on ticket 16's 555, plus the last fixes), ArchUnit and JaCoCo met; run from clean twice after the main edit and once after the last. `ArchitectureTest` and `ModuleConventionsArchTest` are unmodified and cover `parser.burp`.
- Front end `npm run check`: passed.
- Reviewer: Must-fix 0, one Human decision (the DOCTYPE rule, answered (a)); three Should-fix items fixed (a stale comment, the UTF-16/32 refusal documented and tested, log capture in the rejected-file sentinel test); the final gate then found two small inaccuracies (a test accepting two statuses where the ADR named one, and an ADR claiming a move that was not made), both fixed.
- About 18 Burp mutations and every refactor mutation were caught in isolated copies; atomicity is proved with two fault modes (the existing `on` fault, and a new `late` fault that fires at the settlement's commit and also catches `Propagation.MANDATORY` changed to `REQUIRES_NEW`).
- `/code-reviewer` final gate: PASS, no Critical or High finding (build PASS, react PASS; thermo-nuclear, code-review, semgrep, im8 and springboot WARN). The one semgrep ERROR is a false positive on a pre-existing test-harness line. Report: `artifacts/code-reviewer/17-parser-burp-compliance.html`.
- im8 tracked deferrals: as-12 production scanner adapter and file store (ticket 51); lm-16 meters and the pm-6 OpenAPI file (ticket 52).
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- Real Burp exports are untested until ticket 58 (treat it as a release gate): whether `path` carries the query, the form of `host`, the exact `severity` and `confidence` words, the `exportTime` form and zone, the root namespace, the prolog (a processing instruction or a comment inside the DTD subset is refused, as is a UTF-16/32 export, so a real export that carries one would fail closed), and whether a non-base64 export can carry characters XML 1.0 forbids. The type ids and every sample text are invented.
- The Nessus and SCAP atomicity tests still use only the weaker `on` fault, so they would not catch `REQUIRES_NEW`; upgrading them is a one-line change each.
- The Dependency-Check parser still keeps its own `ScanFacts` copy (a 200-character target cut exists in two places) and its own scan-date handling; ADR 0025 says so.
- `XmlCursor.read` swallows every `RuntimeException` with no trace, so a parser bug looks like a bad file to the person and is invisible to operations; log a WARN with the error type only (ticket 52).
- Refactor debt to schedule before ticket 30 adds the next per-occurrence attribute: Finding identity is still three arguments (introduce a single `FindingId`); `Severity.ofScap` and `ofBurp` can collapse; the field loop and `MAX_SHORT_VALUE` are copied per parser; `confidence` is plumbed through six near-identical records; `ScanReading.sections` is dead weight for Burp; the DOCTYPE policy could live in `parser.xml`.
- `FindingView.confidence` and `ReportFileMetadataView.scanDateIsExportTime` have no front-end reader yet (tickets 19, 23 and 30 own the screens). Uploading the same export twice doubles occurrence counts (ticket 21), and there is no aggregate Findings cap per VA Submission (both open since ticket 14).
- No browser run of the one-string front-end change. Medium and Low findings remain open in the compliance report.
