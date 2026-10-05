# 45: Management summary export (PDF) with a diagonal watermark

**Spec:** [Annual VA spec](../spec.md), stories 81, 86.
**Release:** 1
**What to build:** The same management summary is available as a PDF with the classification watermarked across every page.
**Blocked by:** 44 (Management summary export (Excel), watermarked, no vulnerability detail)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 5.3 developer days across stories 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Format PDF renders the same content with the PDF library chosen in ADR 0016: a header and footer with the classification and a diagonal semi-transparent watermark on every page.
- [ ] The PDF contains no vulnerability detail (text extraction in the test), and is downloadable and audited like the Excel export.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
