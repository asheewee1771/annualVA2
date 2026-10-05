# 58: Produce real sample exports from throwaway targets

**Spec:** [Annual VA spec](../spec.md), stories 20a to 20d.
**Release:** 1
**What to build:** The parser samples are regenerated from real tools (Dependency-Check with an NVD key, CIS-CAT Pro or OpenSCAP, Nessus, Burp Suite) run against throwaway targets, never a real System, and replace the reconstructions before UAT.
**Blocked by:** 14 (Dependency-Check parser (XML and JSON)), 15 (Secure Configuration parser: SCAP ARF and XCCDF results), 16 (Secure Configuration parser: Nessus compliance audit), 17 (Burp Suite parser: XML issue export)
**Status:** ready-for-human
**Effort (orchestrator estimate):** about 7.0 developer days across stories 20a (5.0*), 20d (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] One real export per format, from a throwaway target, with the provenance notes updated to say so; a review confirms no real host name or System appears in any sample.
- [ ] The parser tests still pass on the real exports; any mapping change is recorded in the parser tickets' Comments.

## Comments
