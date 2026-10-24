# 24: Agree the Declaration text and its owner

**Spec:** [Annual VA spec](../spec.md), stories 41.
**Release:** 1
**What to build:** The exact wording the SMO/Tech Lead agrees to when submitting, who owns it and how a new version is approved. Until agreed, the app carries a placeholder clearly marked as such.
**Blocked by:** 61 (A VA POC publishes a new version of the Declaration text in the app), for the last checkbox only
**Status:** ready-for-human
**Effort (orchestrator estimate):** about 5.0 developer days across stories 41 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] The Declaration wording is agreed with the PO and the cyber team and recorded under Comments with its version number and effective date.
- [ ] The owner of the text and the process for changing it are named.
- [ ] The agreed text replaces the placeholder seed before UAT.

## Comments

### Decision (2026-10-24)

The agreed wording is not shipped by migration. It is published in the app by a VA POC, through ticket 61 (decision recorded in `docs/agents/reviewer-decisions.md`, "The Declaration text is published in the app by a VA POC").
- **Owner of the text:** the VA POC role. A VA POC publishes each new version in the app.
- **Process for changing it:** set by ticket 61. Open questions there: whether a second approver is needed, and whether a version can take effect from a chosen date.
- **Still open here:** the wording itself, its version and its effective date, agreed with the PO and the cyber team. It must be published through ticket 61 before UAT, replacing the V19 placeholder.
