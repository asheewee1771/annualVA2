# Annual VA tracker

- `spec.md` (and `spec.pdf`): the Gate-1-approved early-draft TRD, compiled with /to-spec on 2026-09-28 and approved on 2026-09-29. It is the authoritative requirements document; its text is kept verbatim.
- `spec.stories.yaml`: the structured stories derived from the spec's User Stories and Releases, the input to /dependency-orchestrator.
- `issues/NN-slug.md`: one ticket per file, numbered in dependency order (blockers first). Format and labels: `docs/agents/issue-tracker.md`, `docs/agents/triage-labels.md`.

The spec's relative links (`map.md`, `issues/NN-*.md` planning tickets, `../../artifacts/spec-compliance/...`, `../../prototypes/...`, `../../CONTEXT.md`) refer to the wayfinding repository it was written in (`asheewee1771/annualVA`). This repository is a clean-room implementation and does not reproduce those planning artefacts; the glossary lives at the repo root `CONTEXT.md`.
