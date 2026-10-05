# 16: Secure Configuration parser: Nessus compliance audit

**Spec:** [Annual VA spec](../spec.md), stories 20c, 21, 25, 28, 29.
**Release:** 1
**What to build:** A `.nessus` compliance export uploaded as the Secure Configuration Scan Report is read into Findings for FAILED and WARNING checks and ERROR checks that did not complete, ignoring PASSED and non-compliance items.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 18.8 developer days across stories 20c (2.0), 21 (1.3), 25 (4.0), 28 (9.5*), 29 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] The parser accepts `NessusClientData_v2` documents and rejects others; compliance results FAILED and WARNING become Findings keyed `nessus|<check id or name>` with one occurrence per host, ERROR becomes a Finding marked as not completed, PASSED and non-compliance ReportItems are ignored; Info maps to INFORMATIONAL, unrated to MEDIUM.
- [ ] The latest `HOST_END` pre-fills the scan date and the host names the target.
- [ ] Sample export with a provenance note; hardened XML parsing.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
