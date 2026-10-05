# Annual VA tracker

- `spec.md` (and `spec.pdf`): the Gate-1-approved early-draft TRD, compiled with /to-spec on 2026-09-28 and approved on 2026-09-29. It is the authoritative requirements document; its text is kept verbatim.
- `spec.stories.yaml`: the structured stories derived from the spec's User Stories and Releases (99 stories; story 20 split into 20 and 20a to 20d), the input to /dependency-orchestrator. Its outputs are `../../artifacts/project-dag.json`, `../../artifacts/processor-output.json` and `../../reports/dev-report.{html,md}`; `../../artifacts/dag-repair-notes.md` records the hand repairs the merged DAG needed.
- `issues/NN-slug.md`: one ticket per file, 59 tickets for Release 1, numbered in dependency order (blockers first) following section 7 of `../../docs/implementation-plan.md`. Tickets 01, 24, 54, 55, 56, 57, 58 and 59 are `ready-for-human`; 02 (the scaffold) is closed; the rest are `ready-for-agent`. Each carries the orchestrator's effort estimate for the stories it covers. Release 2 tickets are generated later and continue the numbering. Format and labels: `docs/agents/issue-tracker.md`, `docs/agents/triage-labels.md`.

How work proceeds: `/do-work` picks the lowest-numbered unblocked `ready-for-agent` ticket, implements it with Seam 1 tests, runs the gates and `/code-reviewer`, ticks the boxes, appends a Verification comment and closes the ticket.

The spec's relative links (`map.md`, `issues/NN-*.md` planning tickets, `../../artifacts/spec-compliance/...`, `../../prototypes/...`, `../../CONTEXT.md`) refer to the wayfinding repository it was written in (`asheewee1771/annualVA`). This repository is a clean-room implementation and does not reproduce those planning artefacts; the glossary lives at the repo root `CONTEXT.md`.
