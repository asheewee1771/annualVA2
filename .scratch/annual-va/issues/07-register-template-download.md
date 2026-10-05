# 07: Download the System Register template

**Spec:** [Annual VA spec](../spec.md), stories 1.
**Release:** 1
**What to build:** A VA POC downloads the fixed Excel template (System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter) from the Register screen.
**Blocked by:** 04 (Roles held in the app and every endpoint denied by default)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 2.3 developer days across stories 1 (2.3*); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/system-register/template` (VA POC only) returns an `.xlsx` with exactly the five columns, header validation hints and one example row, as `Content-Disposition: attachment`.
- [ ] The download is audited; an SMO or Cyber Manager receives 403.
- [ ] The Register screen has a Download template action under the classification banner.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
