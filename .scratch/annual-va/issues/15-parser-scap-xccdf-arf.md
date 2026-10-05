# 15: Secure Configuration parser: SCAP ARF and XCCDF results

**Spec:** [Annual VA spec](../spec.md), stories 20b, 21, 25, 28, 29.
**Release:** 1
**What to build:** A CIS-CAT Pro or OpenSCAP results file (ARF or XCCDF) uploaded as the Secure Configuration Scan Report is read into Findings for failed checks and checks that did not complete, with passed and not-applicable results ignored.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 18.8 developer days across stories 20b (2.0), 21 (1.3), 25 (4.0), 28 (9.5*), 29 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] The parser accepts ARF (`asset-report-collection`) and XCCDF `Benchmark`/`TestResult` (1.1 and 1.2) documents and rejects others as REJECTED_SCHEMA; the ARF size limit and the UI hint to export XCCDF results only when an ARF is too large are in place.
- [ ] Results `fail` become Findings keyed `scap|<rule idref>` with one occurrence per host; `error` and `unknown` become Findings marked as checks that did not complete; `pass`, `notapplicable` and `notchecked` are ignored. Unrated checks map to MEDIUM.
- [ ] `TestResult@end-time` pre-fills the scan date and `target`/`target-address` the target.
- [ ] Sample exports (CIS-CAT XCCDF and OpenSCAP ARF) with provenance notes; hardened XML parsing.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
