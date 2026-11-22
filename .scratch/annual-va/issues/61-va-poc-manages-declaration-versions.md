# 61: A VA POC publishes a new version of the Declaration text in the app

**Spec:** [Annual VA spec](../spec.md), stories 40 and 41 (follow-up raised by ticket 24).
**Release:** 1
**What to build:** Instead of shipping each Declaration wording as a database migration, a VA POC enters and publishes a new version of the Declaration text in the app. Every earlier version stays as it is, and every VA Submission keeps the exact wording it accepted (ADR 0032). The first agreed wording (ticket 24) is published this way before UAT, replacing the placeholder seeded by V19.
**Blocked by:** 25 (Tick the Declaration and submit for review)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised by the ticket 24 decision of 2026-10-24 (`docs/agents/reviewer-decisions.md`, "The Declaration text is published in the app by a VA POC").

- [ ] A VA POC can see every Declaration version: its number, its wording, who published it and when, whether it is current or superseded (the placeholder, version 1, included), and how many VA Submissions accepted it.
- [ ] A VA POC can publish a new version: the wording is checked for length and format (IM8 as-1) and stored append-only as the next version number; nothing edits or deletes an earlier version. Any other role gets 403, and no session gets 401.
- [ ] Publishing needs step-up sign-in, like the other VA POC actions that change what every SMO sees (spec ac-2). One VA POC publishes; there is no second approver.
- [ ] A published version is current at once; there is no effective date.
- [ ] Publishing, and every refused attempt, is audited with the version number and fixed codes only, never the wording.
- [ ] Once a new version is current, a submit that accepted an older version is refused as `declaration-changed` (ADR 0032), so an SMO with the old wording open reads it again and re-ticks.
- [ ] The wording is rendered as text only everywhere it appears.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

**2026-11-22, issue verification (HITL 2):** the three open questions are decided by the user. (1) One VA POC with step-up publishes; no second approver. (2) A version takes effect at once; an SMO with the older wording open is refused as `declaration-changed` (ADR 0032) and re-ticks. (3) The version list shows each version as current or superseded, the placeholder included, with a count of the VA Submissions that accepted it, not a list of them. The decisions are folded into the criteria. It is one vertical slice (a migration if one is needed, the endpoints, a VA POC screen, Seam 1 tests), can be demonstrated alone, and its blocker (25) is built. `declaration_text` is append-only and no disposal opens it (ADR 0050); a new version is an insert, so tickets 62 and 66 do not block this one. Approved for execution.

**2026-11-22, implementation:** two behaviours are left as they are, as product choices: a wording identical to the current version is not refused (it is published as the next version, and every SMO/Tech Lead ticks it again), and two publishes close together both succeed, each as its own version, the later superseding the earlier.
