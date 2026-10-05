# 14: Dependency-Check parser (XML and JSON)

**Spec:** [Annual VA spec](../spec.md), stories 20a, 21, 25, 28.
**Release:** 1
**What to build:** An OWASP Dependency-Check XML or JSON export uploaded as the Dependency-Check Scan Report is read into Findings with their severity and occurrences, and the scan date and project name are pre-filled for the SMO to confirm.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 19.8 developer days across stories 20a (5.0*), 21 (1.3), 25 (4.0), 28 (9.5*); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] The parser accepts the Dependency-Check XML (`analysis` root in the DependencyCheck namespace) and JSON (`reportSchema`, `scanInfo`, `dependencies[]`) shapes and rejects anything else as REJECTED_SCHEMA.
- [ ] Each vulnerability becomes one Finding keyed `dc|<CVE or GHSA>|<package purl or file name>` scoped to the System, with one occurrence per dependency; severity from CVSS v3 then v2, unrated to HIGH, with the five-level scale.
- [ ] `projectInfo/reportDate` pre-fills the scan date and the project name the target; `GET /api/submissions/{id}/findings` lists the Findings with report type, identifier, package, severity, source file and occurrence count.
- [ ] Sample exports carry a provenance note (schema-faithful reconstructions from a throwaway project, to be regenerated from a real scan before release); XML parsing uses hardened factories (no DTD, no external entities).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
