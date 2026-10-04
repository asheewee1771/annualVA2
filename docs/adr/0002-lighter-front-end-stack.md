# A lighter front-end stack: Vite, React, TypeScript and nothing else

Status: accepted

The spec has no React seam: business rules are proven at the HTTP API and the browser is covered by a handful of Playwright scenarios. A router, UI kit, data-fetching or form library would add surface to review and keep current without a test seam to protect it. The front end is Vite + React 19 + TypeScript strict, plain `fetch` wrapped once in `src/api/client.ts`, a 40-line path router in `src/app`, CSS variables lifted from the prototypes, and no component tests.

Consequences: dependency-cruiser forbids router, UI and data libraries; screens stay thin and push rules to the back end; UI regressions are caught by Seam 2 scenarios and type-checking, not unit tests.
