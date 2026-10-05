# 17: Burp Suite parser: XML issue export

**Spec:** [Annual VA spec](../spec.md), stories 20d, 21, 25, 28, 30.
**Release:** 1
**What to build:** A Burp Suite XML issue export uploaded as the Burp Scan Report is read into Findings with severity and confidence, so that Tentative issues can later be marked False Positive with a reason.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 19.8 developer days across stories 20d (2.0), 21 (1.3), 25 (4.0), 28 (9.5*), 30 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] The parser accepts the Burp `issues` export, allowing only Burp's internal DTD subset with entity-expansion limits (every other format forbids DOCTYPE), and rejects others; the UI hints to export without request and response bodies when the file exceeds the limit.
- [ ] Each issue becomes a Finding keyed `burp|<type id>|<host>|<normalised path>` with confidence stored and shown; Information maps to INFORMATIONAL.
- [ ] `issues@exportTime` pre-fills the scan date flagged as export time only; the hosts pre-fill the target.
- [ ] Sample export with a provenance note.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
