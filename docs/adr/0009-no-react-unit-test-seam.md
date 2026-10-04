# No React unit-test seam

Status: accepted

The spec's Testing Decisions name two seams: the HTTP API (Seam 1) for every business rule, and a thin Playwright layer (Seam 2) for a few end-to-end scenarios of the prototyped screens. A React component seam would test behaviour that is either already proven at Seam 1 or presentational. We do not add Vitest or React Testing Library; the front-end gates are type-checking, the production build, Prettier, the no-raw-HTML gate, dependency-cruiser and fallow.

Consequences: components stay thin; the build-check skill's Vitest coverage step is recorded as not applicable in `docs/agents/reviewer-decisions.md`; UI regressions surface in Seam 2.
