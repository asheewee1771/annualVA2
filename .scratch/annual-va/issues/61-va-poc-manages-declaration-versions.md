# 61: A VA POC publishes a new version of the Declaration text in the app

**Spec:** [Annual VA spec](../spec.md), stories 40 and 41 (follow-up raised by ticket 24).
**Release:** 1
**What to build:** Instead of shipping each Declaration wording as a database migration, a VA POC enters and publishes a new version of the Declaration text in the app. Every earlier version stays as it is, and every VA Submission keeps the exact wording it accepted (ADR 0032). The first agreed wording (ticket 24) is published this way before UAT, replacing the placeholder seeded by V19.
**Blocked by:** 25 (Tick the Declaration and submit for review)
**Status:** needs-triage
**Effort (orchestrator estimate):** not estimated; raised by the ticket 24 decision of 2026-10-24 (`docs/agents/reviewer-decisions.md`, "The Declaration text is published in the app by a VA POC").

- [ ] A VA POC can see the current Declaration version, its wording, who published it and when, and the earlier versions.
- [ ] A VA POC can publish a new version: the wording is checked for length and format (IM8 as-1) and stored append-only as the next version number; nothing edits or deletes an earlier version. Any other role gets 403, and no session gets 401.
- [ ] Publishing needs step-up sign-in, like the other VA POC actions that change what every SMO sees (spec ac-2).
- [ ] Publishing, and every refused attempt, is audited with the version number and fixed codes only, never the wording.
- [ ] Once a new version is current, a submit that accepted an older version is refused as `declaration-changed` (ADR 0032), so an SMO with the old wording open reads it again and re-ticks.
- [ ] The wording is rendered as text only everywhere it appears.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

**To decide with the human when this ticket is picked up**
- Does a version need a second approver (for example, one VA POC drafts and the other approves), or is one VA POC with step-up enough? A second approver must not be the drafter.
- Should a version take effect from a chosen date (`effective_from`, for example the start of the next Quarter) rather than at once, so a change does not make SMOs re-tick mid-window? If so, which version does a submit made before that date see?
- Is the placeholder (version 1) shown as superseded, and can a VA POC see which VA Submissions accepted each version?

## Comments
