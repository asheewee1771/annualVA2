# Annual VA

Internal app that runs the organisation's annual vulnerability assessment: VA Cycle, Quarters, System Register, VA Submissions, Scan Reports, Findings, review and endorsement. React front end (`frontend/`), Spring Boot back end (`backend/`).

- Glossary: `CONTEXT.md` (use its terms; avoid the synonyms it lists)
- Spec: `.scratch/annual-va/spec.md` (Gate-1-approved TRD; the authoritative requirements)
- Tickets: `.scratch/annual-va/issues/`
- Decisions: `docs/adr/` and `docs/agents/reviewer-decisions.md` (check before asking again)
- Run book: `README.md`

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default labels (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the root. See `docs/agents/domain.md`.

## Quality gates

- Back end: `cd backend && ./mvnw -B -ntp clean verify` (Seam 1 tests on Testcontainers, ArchUnit, JaCoCo line and branch coverage of at least 80 %)
- Front end: `cd frontend && npm run check` (Prettier, `tsc -b`, `vite build`, no raw HTML, dependency-cruiser, fallow dead-code and health)
- Review: `/code-reviewer` per ticket, report under `artifacts/code-reviewer/`

## Conventions

- Tests drive the HTTP API (Seam 1) with a real PostgreSQL and fake only the boundary adapters (identity provider, malware scanner, mail relay, file store, clock). No React unit tests; business rules are never tested in the browser.
- Report content (CVE ids, rule names, hosts, free text) is returned as data and rendered only as text. It never appears in logs, audit entries or error bodies.
- Business dates use `Asia/Singapore`; timestamps are stored in UTC; time is read only through the `Clock` bean.
- Conventional Commits, one logical change per commit.
