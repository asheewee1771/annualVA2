# 09: Step-up: a fresh sign-in before sensitive actions

**Spec:** [Annual VA spec](../spec.md), stories 77.
**Release:** 1
**What to build:** A sensitive action is refused unless the person signed in again within the last five minutes; the app sends them through a fresh sign-in and brings them back to finish the action. The first sensitive action is the Register apply (next ticket), which is where this is demonstrated end to end.
**Blocked by:** 03 (Sign in through MCC SSO and see my session)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 3.0 developer days across stories 77 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A command marked sensitive calls the step-up check; when the session's `auth_time` is older than the configured freshness window (5 minutes) the API answers 403 with problem type `step-up-required` and a `stepUpPath`.
- [ ] `GET /auth/step-up?intent=...` stores the intent and return path in the session and starts a new authorization request with `prompt=login` and `max_age=0`; the callback refuses a token whose `auth_time` is absent or not fresher than the intent and otherwise marks the session fresh without extending its absolute lifetime.
- [ ] Successful and refused step-ups are audited; the fake issuer can return a stale `auth_time` to prove the refusal.
- [ ] The front end intercepts `step-up-required`, saves the current route and form draft, shows an interstitial explaining why, and after the round trip restores the route; the action is never replayed automatically.
- [ ] If the identity provider ignores `prompt=login` (configurable), the same component ends the session and forces a full sign-in (ADR 0004).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
