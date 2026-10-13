# 09: Step-up: a fresh sign-in before sensitive actions

**Spec:** [Annual VA spec](../spec.md), stories 77.
**Release:** 1
**What to build:** A sensitive action is refused unless the person signed in again within the last five minutes; the app sends them through a fresh sign-in and brings them back to finish the action. The first sensitive action is the Register apply (next ticket), which is where this is demonstrated end to end.
**Blocked by:** 03 (Sign in through MCC SSO and see my session)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 3.0 developer days across stories 77 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A command marked sensitive calls the step-up check; when the session's `auth_time` is older than the configured freshness window (5 minutes) the API answers 403 with problem type `step-up-required` and a `stepUpPath`.
- [x] `GET /auth/step-up?intent=...` stores the intent and return path in the session and starts a new authorization request with `prompt=login` and `max_age=0`; the callback refuses a token whose `auth_time` is absent or not fresher than the intent and otherwise marks the session fresh without extending its absolute lifetime.
- [x] Successful and refused step-ups are audited; the fake issuer can return a stale `auth_time` to prove the refusal.
- [x] The front end intercepts `step-up-required`, saves the current route and form draft, shows an interstitial explaining why, and after the round trip restores the route; the action is never replayed automatically.
- [x] If the identity provider ignores `prompt=login` (configurable), the same component ends the session and forces a full sign-in (ADR 0004).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-05)

**Implemented** (commits `4c42739`, `8dffaa3`, `ac15014`, `d36f795`): `StepUp.require(intent)` answers 403 `step-up-required` with a `stepUpPath` when `auth_time` is older than five minutes (exactly five is allowed); `GET /auth/step-up?intent=&returnTo=` is authenticated-only and starts a new authorization request with `prompt=login` and `max_age=0`; a dedicated provider takes only callbacks that carry a step-up intent and refuses a token with no or stale `auth_time` (older than the start less 30 s skew), no MFA evidence or another person, then refreshes `auth_time` only, so the 8-hour limit is never extended; ECS events `step-up-required`, `step-up-started`, `step-up`, `step-up-refused` and `step-up-full-sign-in` carry user id and intent only; `annualva.step-up.mode` `full-sign-in` ends the session and forces a normal sign-in (ADR 0004). The front end shows an interstitial over the workspace, keeps the route and any draft for the round trip only (cleared at sign-out, on a load without a step-up return and after 15 minutes), restores the route afterwards and never replays the action.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 114 tests, 0 failures, ArchUnit and JaCoCo met. 16 new tests: 15 in `StepUpTest`, 1 in `StepUpFullSignInTest`.
- Front end `npm run check`: passed.
- Reviewer loop: three rounds. Round 1 found the saved route and draft never cleared at sign-out; round 2 and round 3 found 0 Must-fix and 0 Human decisions.
- `/code-reviewer` final gate: first run FAIL on one thermo-nuclear High (step-up forked three shared sign-in classes through an implicit request attribute); fixed with a dedicated provider, typed principal and dedicated handlers; second run PASS (build and semgrep PASS, the other five WARN). Report: `artifacts/code-reviewer/09-step-up-authentication-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open for the owners named**
- Nothing was run against a real or mock Keycloak, and the front end was not run in a browser.
- Ticket 56 and SIT: whether MCC SSO honours `prompt=login` or needs an end-session call. `full-sign-in` mode ends only the app session; if MCC SSO keeps its own session and ignores `prompt`, the person is signed in silently with the old `auth_time` and the sensitive action is refused again.
- In `full-sign-in` mode the sign-in lands on `/`, so the saved route and draft are dropped and the person lands on their first role area. A server redirect to `/?stepup=done` would fix it if a draft must survive that mode.
- The next ticket (Register apply) is where this is shown end to end. Only a test probe endpoint is sensitive today. `takeRestoredDraft` has no consumer yet and `register-apply` is the only entry in the intent wording table.
- Ticket 05: step-up is audited as ECS events only. The audit table, including the "refused request" entries for `step-up-required`, comes with the audit core.
- Medium and Low findings left open in the gate report: the `StepUp` class does a lot and reads `SecurityContextHolder`; `GET /auth/step-up` changes state (in `full-sign-in` mode a cross-site GET ends the app session); `returnTo` admits `/..`; branch coverage of the new classes; the README does not yet say that `full-sign-in` drops the saved route.
- Low: the repository coupling between `StepUp.begin` and the login filter's default authorization-request repository is implicit; add a comment so a future custom repository does not break step-up silently.

### Note from ticket 13 (2026-10-13): a step-up session is not evicted by the person's next sign-in

- **Defect:** a session created by a step-up stays live after the same person signs in again. The one-session rule (`maximumSessions(1)` over the Spring Session registry) does not expire it. At that person's next step-up the strategy then sees two live sessions and expires the current one, so the request after the step-up answers 401 (`/api/csrf` with "Sign in to continue"). In production a stale step-up session could sign a person out at a later step-up.
- **How it showed:** `RegisterApplyTest.applyingNeedsAFreshSignInAndChangesNothingWithoutOne` failed in the full back-end suite (three runs in a row) and in some subsets once ticket 13's tests ran before it. A throwaway test that signs the same VA POC in many times and does a step-up each round failed after about seven rounds with no ticket 13 code involved; the same loop without the step-up passed. The cause was not pinned down (a guess: the principal-name lookup of the registry does not match stepped-up sessions).
- **Stopgap:** the Seam 1 reset endpoint (`harness/TestEndpoints.resetRegister`) deletes signed-in sessions between Register tests. It hides the defect; it must be removed when the real fix lands. A separate task for the fix has been raised; it is not part of ticket 13.
