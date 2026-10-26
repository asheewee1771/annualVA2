# Browser Acceptance Test Report

**Date:** 2026-10-26
**Issue:** tickets 29 to 50 (the implement-spec run); the criteria live in `.scratch/annual-va/issues/`, not in GitHub issues
**Base URL:** none resolved (the app was not started)
**Run Command:** `docker compose up -d`, then `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev-mcc` (README, "Run book")
**Scope:** none run

## Results Summary

| Criterion | Passed | Failed | Skipped | Blocked |
|-----------|--------|--------|---------|---------|
| Every acceptance criterion of tickets 29 to 50 | 0 | 0 | 0 | all |

## Detailed Findings

### All criteria
**Status:** BLOCKED
**Reason:** Two prerequisites are missing on this machine, so no browser scenario could be driven and nothing here is evidence of a pass or a fail:
1. **No way to sign in.** Every screen sits behind sign-in through Keycloak (realm `SSO`), which comes from the sibling repository `D:\projects\mock-m-sso`. That directory does not exist (ticket 01, `ready-for-human`, is the open task to obtain it). Without it `docker compose up -d` can start only `postgres` and `localstack`, so a user cannot sign in, scan a file or receive mail.
2. **`agent-browser` is not installed** (`npm i -g agent-browser && agent-browser install` has not been run). I did not install it, because it would not help while sign-in is blocked.

The skill also expects a GitHub issue for the criteria. This repository's tracker is local markdown (`docs/agents/issue-tracker.md`), so a real run needs a chosen ticket or a chosen set of tickets as the scope.

## Screenshots

None.

## Sign-off

**Result:** BLOCKED
**Blockers:**
- `D:\projects\mock-m-sso` is missing (ticket 01).
- `agent-browser` is not installed.
- Choose the scope: the browser scenarios themselves are ticket 53's Playwright work, and any one ticket's criteria would be the smaller job.

**Not covered by the browser, but covered elsewhere:** the back-end behaviour of every ticket is proven by Seam 1 tests through the HTTP API (1245 tests on the final tree), and the front end passes its gate (type check, build, no raw HTML, dependency rules, dead-code and health). The screens themselves have never been opened in a browser.
