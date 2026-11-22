# 61: A VA POC publishes a new version of the Declaration text in the app

**Spec:** [Annual VA spec](../spec.md), stories 40 and 41 (follow-up raised by ticket 24).
**Release:** 1
**What to build:** Instead of shipping each Declaration wording as a database migration, a VA POC enters and publishes a new version of the Declaration text in the app. Every earlier version stays as it is, and every VA Submission keeps the exact wording it accepted (ADR 0032). The first agreed wording (ticket 24) is published this way before UAT, replacing the placeholder seeded by V19.
**Blocked by:** 25 (Tick the Declaration and submit for review)
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised by the ticket 24 decision of 2026-10-24 (`docs/agents/reviewer-decisions.md`, "The Declaration text is published in the app by a VA POC").

- [x] A VA POC can see every Declaration version: its number, its wording, who published it and when, whether it is current or superseded (the placeholder, version 1, included), and how many VA Submissions accepted it.
- [x] A VA POC can publish a new version: the wording is checked for length and format (IM8 as-1) and stored append-only as the next version number; nothing edits or deletes an earlier version. Any other role gets 403, and no session gets 401.
- [x] Publishing needs step-up sign-in, like the other VA POC actions that change what every SMO sees (spec ac-2). One VA POC publishes; there is no second approver.
- [x] A published version is current at once; there is no effective date.
- [x] Publishing, and every refused attempt, is audited with the version number and fixed codes only, never the wording.
- [x] Once a new version is current, a submit that accepted an older version is refused as `declaration-changed` (ADR 0032), so an SMO with the old wording open reads it again and re-ticks.
- [x] The wording is rendered as text only everywhere it appears.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

**2026-11-22, issue verification (HITL 2):** the three open questions are decided by the user. (1) One VA POC with step-up publishes; no second approver. (2) A version takes effect at once; an SMO with the older wording open is refused as `declaration-changed` (ADR 0032) and re-ticks. (3) The version list shows each version as current or superseded, the placeholder included, with a count of the VA Submissions that accepted it, not a list of them. The decisions are folded into the criteria. It is one vertical slice (a migration if one is needed, the endpoints, a VA POC screen, Seam 1 tests), can be demonstrated alone, and its blocker (25) is built. `declaration_text` is append-only and no disposal opens it (ADR 0050); a new version is an insert, so tickets 62 and 66 do not block this one. Approved for execution.

**2026-11-22, implementation:** two behaviours are left as they are, as product choices: a wording identical to the current version is not refused (it is published as the next version, and every SMO/Tech Lead ticks it again), and two publishes close together both succeed, each as its own version, the later superseding the earlier.

### Verification (2026-11-23)

**What was built.** A VA POC lists every Declaration version (`GET /api/declaration-text/versions`): number, wording, publisher and time (null for the V19 placeholder), current or superseded, and the count of VA Submissions that accepted it. A VA POC publishes the next version (`POST`, step-up intent `declaration-publish`, no second approver); it is current at once and nothing edits an earlier version. Migration V46 adds `published_by` and `published_at`; one store (`DeclarationStore`) owns every `declaration_text` statement, and an advisory lock keeps two publishes from taking the same number. The wording is cleaned and bounded (1 to 4,000 characters after cleaning, a body over 8,000 characters refused before it is parsed). Anyone but a VA POC gets 403 by a URL rule before any body is read. Audited `DECLARATION_PUBLISHED` with `declaration_version` or `reason=invalid-field`, never the wording. The VA POC area gains a Declaration page that shows each wording as text. Commits `40af5e5` (feature), `4c5b081` (ADR 0032 amendment, README, ticket), `72fd689` (report), merged by `4ffdacf`. The migration was renumbered from V45 to V46 at merge because ticket 66's V45 was already on `main`.

**Decided by the user on 2026-11-22.** One VA POC with step-up publishes; no second approver. A version is current at once; an SMO with the older wording open is refused as `declaration-changed` and re-ticks. The list shows current or superseded with a count, not the VA Submissions themselves. Left as product choices: identical wording is not refused, and two close publishes both succeed in order.

**How it was verified.**
- Seam 1 (`DeclarationPublishTest`, 7 tests): the placeholder is the one current version before any publish; a published version is current at once and earlier versions and accepted wording do not change; a submit that accepted an older version is refused `declaration-changed` and passes once the new one is ticked; publishing needs a fresh sign-in and is audited by version number only; only a VA POC reads or publishes; anyone else gets 403 before an over-long body is read; the wording is cleaned and length-checked and every refusal is audited without it. `PermissionMatrixTest` and `AuditCompletenessTest` cover the new endpoints and action.
- Back-end gate on `main` after the merge (`4ffdacf`): `./mvnw -B -ntp clean verify`, 1,371 tests, 0 failures, 1 skipped, coverage checks met, BUILD SUCCESS. Front-end gate: `npm run check` exits 0 on `main`.
- `/code-reviewer`: PASS, no Critical, High or Medium open (`artifacts/code-reviewer/61-va-poc-manages-declaration-versions-compliance.html`). Three rounds; the Mediums of rounds one and two (two stores owning `declaration_text`; 400 before 403 for a non-VA-POC; an oversized body from a non-VA-POC audited as a refused publish) were fixed and confirmed.

**Not covered.** Publishing the first agreed wording (ticket 24) before UAT is a step for a VA POC in the running app, not code. The identity provider's MFA on the step-up sign-in is a manual item. No browser run was made.

All eight boxes are ticked.
