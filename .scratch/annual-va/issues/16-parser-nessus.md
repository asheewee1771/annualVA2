# 16: Secure Configuration parser: Nessus compliance audit

**Spec:** [Annual VA spec](../spec.md), stories 20c, 21, 25, 28, 29.
**Release:** 1
**What to build:** A `.nessus` compliance export uploaded as the Secure Configuration Scan Report is read into Findings for FAILED and WARNING checks and ERROR checks that did not complete, ignoring PASSED and non-compliance items.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** closed
**Effort (orchestrator estimate):** about 18.8 developer days across stories 20c (2.0), 21 (1.3), 25 (4.0), 28 (9.5*), 29 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] The parser accepts `NessusClientData_v2` documents and rejects others; compliance results FAILED and WARNING become Findings keyed `nessus|<check id or name>` with one occurrence per host, ERROR becomes a Finding marked as not completed, PASSED and non-compliance ReportItems are ignored; Info maps to INFORMATIONAL, unrated to MEDIUM.
- [x] The latest `HOST_END` pre-fills the scan date and the host names the target.
- [x] Sample export with a provenance note; hardened XML parsing.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-14)

**Implemented** (commits `9b3e99a`, `d9fe997`, `e57d7d2`, `c56a1f9`, `50d589d`, `4416772`): a `.nessus` file (`NessusClientData_v2`) uploaded as the Secure Configuration Scan Report that has passed the scan, the type check and the schema check is parsed inside the pipeline's settlement transaction into Findings, as for tickets 14 and 15. New `parser.nessus.NessusXmlParser` streams the document through `XmlCursor` and reads only `NessusClientData_v2/Report/ReportHost`, its `HostProperties` and its `ReportItem`s, skipping the Policy, descriptions and plugin output without building them. A `ReportItem` is a compliance check only if it has a `compliance-result`: `FAILED` and `WARNING` become Findings, `ERROR` becomes a Finding that did not complete (V11 and the read-time `bool_and` are reused), and `PASSED`, any other result word and every non-compliance item are ignored. The key is `nessus|<check id>`, else `nessus|<check name>`; one occurrence per `ReportHost@name`; the `severity` attribute maps 0 to INFORMATIONAL, 1 LOW, 2 MEDIUM, 3 HIGH, 4 CRITICAL (the words are read too; missing or any other value is MEDIUM). The latest `HOST_END` pre-fills the scan date (Nessus's zone-less `Tue Jul 20 10:03:44 2027` form, taken as UTC, weekday must match the date) and the distinct hosts named (at most 50, cut to 200 characters) the target, through ticket 14's audited metadata read. Another root is `REJECTED_TYPE` for a Secure Configuration upload; malformed shapes are `REJECTED_SCHEMA`; the 50 MB limit equals the request ceiling, so an over-limit file gets 413 at upload (ADR 0021). The parser has no counting or merging of its own: the caps and the merge rule stay in `FindingsBuilder` and `Occurrence.merge`. Shared code added: `parser.ScanFacts` (the latest scan date and hosts named; the SCAP parser now uses it and its private copy is gone) and `ReportText.firstClean`; `ReportDates` reads the Nessus form. A hand-written sample with a provenance note and README is under `samples/nessus/`. One front-end string: the Secure Configuration too-large hint now also names the 50 MB Nessus limit. Build config: `backend/pom.xml` gets a surefire `argLine` of `@{argLine} -Xmx1g` (late-bound so JaCoCo's agent still attaches, with an empty default property), because the 49 MB report tests need about 512 MB. ADR 0024 records the design; one-line amendments to ADRs 0022 and 0023.

**Provenance of this work.** The first implementer run on this ticket was cut off by a network error before it reported; it was resumed, re-checked the tree and ran the gates, and added one test change (a root-level `ReportHost` decoy in `NessusRulesTest`) after a mutation survived. The reviewer then read the whole tree.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 555 tests, 0 failures, 0 skipped (31 new on ticket 15's 524), ArchUnit and JaCoCo met; two consecutive runs from clean after the last edit. `ArchitectureTest` and `ModuleConventionsArchTest` are unmodified; the existing `parser..` layer rule and the SecureXml-only rule already cover `parser.nessus`.
- Front end `npm run check`: passed.
- Reviewer: Must-fix 0, no Human decision; ticket 14 and 15 behaviour confirmed preserved by reading the diff.
- 21 mutation checks in an isolated copy (main tree never mutated), all caught after one fix: ERROR kept as a failed check, key on name first, WARNING ignored, PASSED kept, an item with no result kept, unrated HIGH, Info as LOW, `ReportHost` read wherever it is (this one initially survived and the decoy test was added), `HOST_START` or a tag outside `HostProperties` giving the date, earliest date, the date forms, caps not enforced, over-long key accepted, a host with no name accepted, Findings recorded outside the settlement transaction, `bool_and` to `bool_or`, `Report` not required, shown name uncut, parser unregistered.
- `/code-reviewer` final gate: PASS, no Critical or High finding (build PASS, semgrep PASS, react PASS; thermo-nuclear, code-review, im8 and springboot WARN). Report: `artifacts/code-reviewer/16-parser-nessus-compliance.html`.
- im8 tracked deferrals: as-12 production scanner adapter and file store (ticket 51); lm-16 meters and the OpenAPI entries (ticket 52).
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- Real Nessus exports are untested until ticket 58: the severity a real export writes on failed compliance items (if it writes `0`, every failure would read Informational, as the ticket says Info maps to INFORMATIONAL), the stability of check ids, the `cm` namespace (elements are matched by local name and full path, not by namespace, because the URI could not be verified) and the `HOST_END` format and zone. Treat ticket 58 as a gate for this parser before release.
- The date is read as UTC and is only a suggestion for the person to confirm (confirm the behaviour with ticket 23).
- The build check's PASS rests on a run in a clean copy of `backend/`, because its first in-place run was killed by a Docker disruption; the implementer's own two clean runs were in place.
- Medium findings left open:
  - The XML scaffolding (a leak-safe parse wrapper, a reading holder, the child-walk loop) is now copied in three parsers; extract it into `XmlCursor` or a shared helper before the Burp parser (ticket 17).
  - The Nessus severity scale and the unrated-is-MEDIUM policy sit in the parsers (`NessusXmlParser`, `ScapXmlParser`); move them into `Severity`.
  - Finding identity (identifier and subject) is passed redundantly, with the `nessus|` and `scap|` prefixes built by string concatenation in each parser.
  - The 200-character target cut exists twice (`ScanFacts` and `DependencyCheckFindings`), and `ScanFacts.named` validates but cleans nothing.
  - `NessusLimitsTest` (the 20,000-Finding and 50,000-occurrence cases) adds minutes to a clean `verify`; the cap logic is shared in `FindingsBuilder`, so trim it to Nessus-specific cases.
  - The surefire `-Xmx1g` is hard-coded and appended last, and the measurement behind it (the 49 MB tests pass at 512 MB) is only in ADR 0024; use a named property.
- No Nessus case in `XmlParserDepthTest`; key and host length boundaries are tested only well over the limit; the swallowed parser `RuntimeException` leaves no log signal (ticket 52).
- No browser run of the one-string front-end change. Uploading the same export twice doubles occurrence counts until ticket 21; no aggregate Findings cap per VA Submission (both open from ticket 14).
- Medium and Low findings remain open in the compliance report.
